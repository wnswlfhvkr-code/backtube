// Strict public contract for sanitized Backtube app fault reports.
// Enum values mirror app SanitizedAppFault (Fault/Component), the authoritative contract.
// Errors raised here carry only fixed messages and never echo request input.

export const FAULTS = Object.freeze([
  'NULL_POINTER',
  'ILLEGAL_STATE',
  'INDEX_BOUNDS',
  'CONCURRENT_MODIFICATION',
  'ASSERTION',
]);
export const COMPONENTS = Object.freeze(['PLAYER', 'UI', 'APP']);
export const SCHEMA_VERSION = 1;
export const MIN_ANDROID_API = 23;
export const MAX_ANDROID_API = 100;
export const MAX_BODY_BYTES = 512;
export const FAULT_PATH = '/v1/fault';
export const ISSUE_TITLE = '[App fault] Automatic sanitized report';

const FIELDS = Object.freeze(['schema', 'fault', 'component', 'app_version_code', 'android_api']);

export class ProtocolError extends Error {
  constructor() {
    super('invalid request');
    this.name = 'ProtocolError';
  }
}

/**
 * Returns a fresh object with exactly the five contract fields, or null when the input
 * is not a plain object carrying exactly those fields with allowlisted values.
 */
export function validatePayload(input) {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) return null;
  const proto = Object.getPrototypeOf(input);
  if (proto !== Object.prototype && proto !== null) return null;
  const keys = Reflect.ownKeys(input);
  if (keys.length !== FIELDS.length) return null;

  const values = {};
  for (const field of FIELDS) {
    const descriptor = Object.getOwnPropertyDescriptor(input, field);
    if (!descriptor || !('value' in descriptor)) return null;
    values[field] = descriptor.value;
  }

  const { schema, fault, component, app_version_code: version, android_api: api } = values;
  if (schema !== SCHEMA_VERSION) return null;
  if (typeof fault !== 'string' || !FAULTS.includes(fault)) return null;
  if (typeof component !== 'string' || !COMPONENTS.includes(component)) return null;
  if (!Number.isSafeInteger(version) || version <= 0) return null;
  if (!Number.isSafeInteger(api) || api < MIN_ANDROID_API || api > MAX_ANDROID_API) return null;

  return {
    schema: SCHEMA_VERSION,
    fault,
    component,
    app_version_code: version,
    android_api: api,
  };
}

function requireValid(payload) {
  const valid = validatePayload(payload);
  if (valid === null) throw new ProtocolError();
  return valid;
}

/** Deterministic public dedup tuple. Not secret and not a hash; contains only enums and numbers. */
export function signature(payload) {
  const p = requireValid(payload);
  return `backtube-fault/v${p.schema}/${p.fault}/${p.component}/${p.app_version_code}/${p.android_api}`;
}

/** Exact server-rendered marker line used to recognize the relay's own issues. */
export function marker(payload) {
  return `<!-- backtube-fault-marker: ${signature(payload)} -->`;
}

/** Fixed title and a body made only of enums, numbers and the server marker. */
export function renderIssue(payload) {
  const p = requireValid(payload);
  const body = [
    `Automatic sanitized app fault report (schema ${p.schema}).`,
    '',
    `- fault: ${p.fault}`,
    `- component: ${p.component}`,
    `- app_version_code: ${p.app_version_code}`,
    `- android_api: ${p.android_api}`,
    '',
    'This report contains only allowlisted enum values and numbers.',
    'It was received by a public relay endpoint and may be fabricated.',
    '',
    marker(p),
    '',
  ].join('\n');
  return { title: ISSUE_TITLE, body };
}

function isJsonContentType(value) {
  if (typeof value !== 'string') return false;
  const parts = value.split(';');
  if (parts[0].trim().toLowerCase() !== 'application/json') return false;
  for (const raw of parts.slice(1)) {
    const param = raw.trim();
    if (param === '') continue;
    const eq = param.indexOf('=');
    if (eq < 0) return false;
    const name = param.slice(0, eq).trim().toLowerCase();
    let paramValue = param.slice(eq + 1).trim().toLowerCase();
    if (paramValue.length >= 2 && paramValue.startsWith('"') && paramValue.endsWith('"')) {
      paramValue = paramValue.slice(1, -1);
    }
    if (name !== 'charset' || paramValue !== 'utf-8') return false;
  }
  return true;
}

async function readBounded(body, limit) {
  if (body === null || body === undefined) return new Uint8Array(0);
  if (typeof body.getReader !== 'function') throw new ProtocolError();
  const reader = body.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!ArrayBuffer.isView(value)) throw new ProtocolError();
      total += value.byteLength;
      if (total > limit) throw new ProtocolError();
      chunks.push(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
    }
  } catch {
    try {
      await reader.cancel();
    } catch {
      // Ignore: the request is rejected regardless.
    }
    throw new ProtocolError();
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Ignore: lock state is irrelevant after completion.
    }
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

/**
 * Reads and validates a POST /v1/fault JSON request. The 512-byte bound is enforced while
 * streaming; Content-Length is only used to reject early, never trusted as the limit.
 */
export async function readPayload(request) {
  if (!request || request.method !== 'POST') throw new ProtocolError();
  let path;
  try {
    path = new URL(request.url).pathname;
  } catch {
    throw new ProtocolError();
  }
  if (path !== FAULT_PATH) throw new ProtocolError();
  const headers = request.headers;
  if (!headers || typeof headers.get !== 'function') throw new ProtocolError();
  if (!isJsonContentType(headers.get('content-type'))) throw new ProtocolError();

  const declared = headers.get('content-length');
  if (declared !== null) {
    const trimmed = declared.trim();
    if (!/^[0-9]{1,6}$/.test(trimmed) || Number(trimmed) > MAX_BODY_BYTES) throw new ProtocolError();
  }

  const bytes = await readBounded(request.body, MAX_BODY_BYTES);
  let parsed;
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    parsed = JSON.parse(text);
  } catch {
    throw new ProtocolError();
  }
  return requireValid(parsed);
}
