// Server-only GitHub App client for the Backtube fault relay.
// Uses only Workers-standard Web APIs (fetch, crypto.subtle, TextEncoder/TextDecoder, atob/btoa).
// Keys, JWTs and installation tokens live only in memory for one operation. They are never
// logged, persisted, cached to files, returned to callers or included in errors.
// Raw GitHub bodies, headers and exception text are never surfaced.

import { validatePayload, renderIssue } from './protocol.mjs';

export { renderIssue } from './protocol.mjs';

const API_ORIGIN = 'https://api.github.com';
const OWNER = 'wnswlfhvkr-code';
const REPO = 'backtube';
const FULL_NAME = `${OWNER}/${REPO}`;
const ISSUES_PATH = `/repos/${OWNER}/${REPO}/issues`;

const TIMEOUT_MS = 20_000;
const MAX_RESPONSE_BYTES = 131_072;
const PAGE_SIZE = 100;
const MAX_PAGES = 10;
const JWT_BACKDATE_SEC = 60;
const JWT_LIFETIME_SEC = 8 * 60;

export const MIN_RETRY_AFTER_MS = 1_000;
export const MAX_RETRY_AFTER_MS = 24 * 60 * 60 * 1000;
export const DEFAULT_RETRY_AFTER_MS = 60_000;

const FAILED = Object.freeze({ kind: 'failed' });
const UNCERTAIN = Object.freeze({ kind: 'uncertain' });
const ABSENT = Object.freeze({ kind: 'absent' });

const BASE_HEADERS = Object.freeze({
  accept: 'application/vnd.github+json',
  'x-github-api-version': '2022-11-28',
  'user-agent': 'backtube-fault-relay',
});

function confirmed(issue) {
  return Object.freeze({ kind: 'confirmed', issue });
}

function rateLimited(retryAfterMs) {
  return Object.freeze({ kind: 'rate_limited', retryAfterMs });
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isPositiveInt(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function clampRetry(ms) {
  if (!Number.isFinite(ms)) return DEFAULT_RETRY_AFTER_MS;
  return Math.min(MAX_RETRY_AFTER_MS, Math.max(MIN_RETRY_AFTER_MS, Math.ceil(ms)));
}

const HTTP_DATE = /^[A-Za-z]{3}, \d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT$/;

/**
 * Parses a Retry-After header value (delta seconds or IMF-fixdate HTTP-date).
 * Result is bounded to [1 second, 24 hours]; missing or invalid values yield 60 seconds.
 */
export function parseRetryAfter(value, nowMs) {
  if (typeof value !== 'string') return DEFAULT_RETRY_AFTER_MS;
  const trimmed = value.trim();
  if (/^[0-9]{1,10}$/.test(trimmed)) return clampRetry(Number(trimmed) * 1000);
  if (HTTP_DATE.test(trimmed) && Number.isFinite(nowMs)) {
    const at = Date.parse(trimmed);
    if (Number.isFinite(at)) return clampRetry(at - nowMs);
  }
  return DEFAULT_RETRY_AFTER_MS;
}

function retryAfterFrom(headers, nowMs) {
  const retryAfter = headers.get('retry-after');
  if (retryAfter !== null) return parseRetryAfter(retryAfter, nowMs);
  const remaining = headers.get('x-ratelimit-remaining');
  const reset = headers.get('x-ratelimit-reset');
  if (remaining !== null && remaining.trim() === '0' && reset !== null && /^[0-9]{1,12}$/.test(reset.trim())) {
    return clampRetry(Number(reset.trim()) * 1000 - nowMs);
  }
  return DEFAULT_RETRY_AFTER_MS;
}

function bytesToBinary(bytes) {
  let out = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    out += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
  }
  return out;
}

function base64url(bytes) {
  return btoa(bytesToBinary(bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlJson(value) {
  return base64url(new TextEncoder().encode(JSON.stringify(value)));
}

const PKCS8_PEM = /^\s*-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\s]+)-----END PRIVATE KEY-----\s*$/;

function pemToDer(pem) {
  const match = PKCS8_PEM.exec(pem);
  if (!match) return null;
  const b64 = match[1].replace(/\s+/g, '');
  if (b64.length === 0) return null;
  let binary;
  try {
    binary = atob(b64);
  } catch {
    return null;
  }
  const der = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) der[i] = binary.charCodeAt(i);
  return der;
}

function normalizeId(value) {
  const text = typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value;
  if (typeof text !== 'string' || !/^[1-9][0-9]{0,19}$/.test(text)) return null;
  return text;
}

function validConfig(options) {
  if (!isPlainObject(options)) return null;
  const appId = normalizeId(options.appId);
  const installationId = normalizeId(options.installationId);
  const botLogin = options.botLogin;
  const privateKeyPem = options.privateKeyPem;
  const fetchFn = options.fetch === undefined ? globalThis.fetch : options.fetch;
  const now = options.now === undefined ? Date.now : options.now;
  const enabled = options.enabled === undefined ? () => true : options.enabled;
  if (appId === null || installationId === null) return null;
  if (typeof botLogin !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9-]{0,98}\[bot\]$/.test(botLogin)) return null;
  if (typeof privateKeyPem !== 'string' || !PKCS8_PEM.test(privateKeyPem)) return null;
  if (typeof fetchFn !== 'function' || typeof now !== 'function') return null;
  if (typeof enabled !== 'function') return null;
  if (!globalThis.crypto || !globalThis.crypto.subtle) return null;
  return { appId, installationId, botLogin, privateKeyPem, fetchFn, now, enabled };
}

// Internal signal: reporting was disabled locally before a request was sent. No request was made,
// so this is a definite local failure, never an uncertain service outcome.
class Stopped extends Error {
  constructor() {
    super('reporting disabled');
    this.name = 'Stopped';
  }
}

class ResponseUnreadable extends Error {
  constructor() {
    super('unreadable response');
    this.name = 'ResponseUnreadable';
  }
}

async function discard(response) {
  try {
    if (response.body && typeof response.body.cancel === 'function') await response.body.cancel();
  } catch {
    // Ignore: the body is intentionally unused.
  }
}

async function readJsonBounded(response) {
  const body = response.body;
  if (!body || typeof body.getReader !== 'function') throw new ResponseUnreadable();
  const reader = body.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!ArrayBuffer.isView(value)) throw new ResponseUnreadable();
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) throw new ResponseUnreadable();
      chunks.push(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
    }
  } catch {
    try {
      await reader.cancel();
    } catch {
      // Ignore: response is treated as unreadable regardless.
    }
    throw new ResponseUnreadable();
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Ignore.
    }
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    throw new ResponseUnreadable();
  }
}

/**
 * Creates the GitHub App client. Invalid or missing configuration produces a client whose
 * methods return { kind: 'failed' } without performing any HTTP request.
 */
export function createGitHubClient(options) {
  const config = validConfig(options);
  if (config === null) {
    return Object.freeze({
      createIssue: async () => FAILED,
      findIssue: async () => FAILED,
    });
  }

  const { appId, installationId, botLogin, privateKeyPem, fetchFn, now, enabled } = config;
  let keyPromise = null;

  // Synchronous fail-closed check: only an explicit `true` permits a request.
  function stillEnabled() {
    try {
      return enabled() === true;
    } catch {
      return false;
    }
  }

  function currentMs() {
    const value = now();
    if (!Number.isFinite(value)) throw new ResponseUnreadable();
    return value;
  }

  function importKey() {
    if (keyPromise === null) {
      const der = pemToDer(privateKeyPem);
      keyPromise = der === null
        ? Promise.reject(new ResponseUnreadable())
        : globalThis.crypto.subtle.importKey(
            'pkcs8',
            der,
            { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
            false,
            ['sign'],
          );
      keyPromise.catch(() => {});
    }
    return keyPromise;
  }

  async function appJwt() {
    const nowSec = Math.floor(currentMs() / 1000);
    const header = base64urlJson({ alg: 'RS256', typ: 'JWT' });
    const claims = base64urlJson({
      iat: nowSec - JWT_BACKDATE_SEC,
      exp: nowSec + JWT_LIFETIME_SEC,
      iss: appId,
    });
    const signingInput = `${header}.${claims}`;
    const key = await importKey();
    const signature = await globalThis.crypto.subtle.sign(
      { name: 'RSASSA-PKCS1-v1_5' },
      key,
      new TextEncoder().encode(signingInput),
    );
    return `${signingInput}.${base64url(new Uint8Array(signature))}`;
  }

  // Performs one request with a 20s timeout covering headers and body, redirects disabled.
  // Network failures throw; unreadable/oversized/malformed JSON sets parsed=false.
  async function exchange(path, init, wantsBody) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      // Rechecked immediately before every request with no await in between.
      if (!stillEnabled()) throw new Stopped();
      const response = await fetchFn(`${API_ORIGIN}${path}`, {
        ...init,
        redirect: 'error',
        signal: controller.signal,
      });
      const status = response.status;
      const headers = response.headers;
      if (!wantsBody(status)) {
        await discard(response);
        return { status, headers, parsed: false, data: undefined };
      }
      try {
        const data = await readJsonBounded(response);
        return { status, headers, parsed: true, data };
      } catch {
        return { status, headers, parsed: false, data: undefined };
      }
    } finally {
      clearTimeout(timer);
    }
  }

  function validTokenResponse(data, nowMs) {
    if (!isPlainObject(data)) return null;
    const { token, permissions, repositories } = data;
    if (typeof token !== 'string' || !/^[A-Za-z0-9_.-]{1,512}$/.test(token)) return null;
    if (data.repository_selection !== 'selected') return null;
    if (!isPlainObject(permissions)) return null;
    for (const name of Object.keys(permissions)) {
      if (name !== 'issues' && name !== 'metadata') return null;
    }
    if (permissions.issues !== 'write') return null;
    if (permissions.metadata !== undefined && permissions.metadata !== 'read') return null;
    if (!Array.isArray(repositories) || repositories.length !== 1) return null;
    const repo = repositories[0];
    if (!isPlainObject(repo) || repo.full_name !== FULL_NAME || repo.name !== REPO) return null;
    if (data.expires_at !== undefined) {
      if (typeof data.expires_at !== 'string') return null;
      const expires = Date.parse(data.expires_at);
      if (!Number.isFinite(expires) || expires <= nowMs) return null;
    }
    return token;
  }

  // Returns { token } or a terminal result (failed / rate_limited). Never throws.
  async function installationToken() {
    let jwt;
    try {
      jwt = await appJwt();
    } catch {
      return { result: FAILED };
    }
    let res;
    try {
      res = await exchange(
        `/app/installations/${installationId}/access_tokens`,
        {
          method: 'POST',
          headers: { ...BASE_HEADERS, authorization: `Bearer ${jwt}`, 'content-type': 'application/json' },
          body: JSON.stringify({ repositories: [REPO], permissions: { issues: 'write' } }),
        },
        (status) => status === 201,
      );
    } catch {
      return { result: FAILED };
    } finally {
      jwt = undefined;
    }
    let nowMs;
    try {
      nowMs = currentMs();
    } catch {
      return { result: FAILED };
    }
    if (res.status === 429) return { result: rateLimited(retryAfterFrom(res.headers, nowMs)) };
    if (res.status !== 201 || !res.parsed) return { result: FAILED };
    const token = validTokenResponse(res.data, nowMs);
    if (token === null) return { result: FAILED };
    return { token };
  }

  function tokenHeaders(token) {
    return { ...BASE_HEADERS, authorization: `Bearer ${token}` };
  }

  async function createIssue(payload) {
    const valid = validatePayload(payload);
    if (valid === null) return FAILED;
    const rendered = renderIssue(valid);

    const acquired = await installationToken();
    if (acquired.result) return acquired.result;

    let res;
    try {
      res = await exchange(
        ISSUES_PATH,
        {
          method: 'POST',
          headers: { ...tokenHeaders(acquired.token), 'content-type': 'application/json' },
          body: JSON.stringify({ title: rendered.title, body: rendered.body }),
        },
        (status) => status === 201,
      );
    } catch (error) {
      // Disabled before the request was sent: nothing reached GitHub.
      if (error instanceof Stopped) return FAILED;
      // Timeout, reset or redirect: the issue may or may not exist. Never retried here.
      return UNCERTAIN;
    }

    if (res.status === 201) {
      if (res.parsed && isPlainObject(res.data) && isPositiveInt(res.data.number)) {
        return confirmed(res.data.number);
      }
      return UNCERTAIN;
    }
    if (res.status === 429) {
      let nowMs;
      try {
        nowMs = currentMs();
      } catch {
        return UNCERTAIN;
      }
      return rateLimited(retryAfterFrom(res.headers, nowMs));
    }
    if (res.status === 401 || res.status === 403) return FAILED;
    if (res.status >= 400 && res.status < 500) return FAILED;
    return UNCERTAIN;
  }

  function isOwnIssue(item, expectedBody) {
    if (!isPlainObject(item)) return false;
    if (Object.prototype.hasOwnProperty.call(item, 'pull_request')) return false;
    if (!isPositiveInt(item.number)) return false;
    const user = item.user;
    if (!isPlainObject(user) || user.login !== botLogin || user.type !== 'Bot') return false;
    return item.body === expectedBody;
  }

  async function findIssue(payload) {
    const valid = validatePayload(payload);
    if (valid === null) return FAILED;
    const expectedBody = renderIssue(valid).body;

    const acquired = await installationToken();
    if (acquired.result) return acquired.result;

    for (let page = 1; page <= MAX_PAGES; page += 1) {
      const query = new URLSearchParams({
        state: 'all',
        creator: botLogin,
        sort: 'created',
        direction: 'asc',
        per_page: String(PAGE_SIZE),
        page: String(page),
      });
      let res;
      try {
        res = await exchange(
          `${ISSUES_PATH}?${query.toString()}`,
          { method: 'GET', headers: tokenHeaders(acquired.token) },
          (status) => status === 200,
        );
      } catch {
        return FAILED;
      }
      if (res.status === 429) {
        let nowMs;
        try {
          nowMs = currentMs();
        } catch {
          return FAILED;
        }
        return rateLimited(retryAfterFrom(res.headers, nowMs));
      }
      if (res.status !== 200 || !res.parsed || !Array.isArray(res.data) || res.data.length > PAGE_SIZE) {
        return FAILED;
      }
      for (const item of res.data) {
        if (isOwnIssue(item, expectedBody)) return confirmed(item.number);
      }
      if (res.data.length < PAGE_SIZE) return ABSENT;
    }
    // Page cap reached without a short final page: absence is not proven.
    return FAILED;
  }

  return Object.freeze({ createIssue, findIssue });
}
