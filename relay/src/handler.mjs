// Request handler for the Backtube fault relay Durable Object.
//
// createRelayHandler({ ledger, github, enabled, now }) -> async (Request) => Response
//   ledger  - Ledger from ./ledger.mjs (reserve/complete).
//   github  - { createIssue(issue, payload), findIssue(marker, payload) } resolving to
//             { kind: 'confirmed', issue } | { kind: 'absent' } |
//             { kind: 'rate_limited', retryAfterMs } | { kind: 'uncertain' } | { kind: 'failed' }.
//             The first argument is the server-rendered issue / exact marker; the second is the
//             validated contract payload (used by the real GitHub App client adapter).
//   enabled - boolean or synchronous function; consulted immediately before every provider call
//             (no await between the check and the call) and again after a lookup returns.
//   now     - () => epoch ms; read fresh whenever time is needed, including at completion.
//
// Responses are fixed JSON: { status } or { status, issue_number } for confirmed issues.
// Nothing from the request, the provider or exceptions is echoed, and nothing is logged.
import { FAULT_PATH, readPayload, renderIssue, marker } from './protocol.mjs';

const MAX_RETRY_SECONDS = 24 * 60 * 60;
const DEFAULT_RETRY_SECONDS = 60;

const BASE_HEADERS = Object.freeze({
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
});

const UNCERTAIN = Object.freeze({ kind: 'uncertain' });

function reply(httpStatus, status, extraHeaders, issueNumber) {
  const body = issueNumber === undefined ? { status } : { status, issue_number: issueNumber };
  return new Response(JSON.stringify(body), {
    status: httpStatus,
    headers: { ...BASE_HEADERS, ...(extraHeaders ?? {}) },
  });
}

function clampSeconds(seconds) {
  if (!Number.isFinite(seconds)) return DEFAULT_RETRY_SECONDS;
  return Math.min(MAX_RETRY_SECONDS, Math.max(1, Math.ceil(seconds)));
}

/** Fixed, input-free responses shared by the handler and the Worker entry point. */
export const responses = Object.freeze({
  notFound: () => reply(404, 'invalid'),
  methodNotAllowed: () => reply(405, 'invalid', { allow: 'POST' }),
  invalid: () => reply(400, 'invalid'),
  disabled: () => reply(503, 'disabled'),
  error: () => reply(503, 'error'),
  pending: () => reply(202, 'pending'),
  retryLater: (seconds) => reply(429, 'pending', { 'retry-after': String(clampSeconds(seconds)) }),
  created: (issue) => reply(201, 'created', undefined, issue),
  duplicate: (issue) => reply(200, 'duplicate', undefined, issue),
});

/** Returns a 404/405 response unless the request is POST /v1/fault; null when routable. */
export function routeRequest(request) {
  let path;
  try {
    path = new URL(request.url).pathname;
  } catch {
    return responses.notFound();
  }
  if (path !== FAULT_PATH) return responses.notFound();
  if (request.method !== 'POST') return responses.methodNotAllowed();
  return null;
}

function isPositiveInt(value) {
  return Number.isSafeInteger(value) && value > 0;
}

// Maps any provider result to a small closed set; anything unexpected is ambiguous.
function normalize(result) {
  if (result === null || typeof result !== 'object') return UNCERTAIN;
  switch (result.kind) {
    case 'confirmed':
      return isPositiveInt(result.issue) ? { kind: 'confirmed', issue: result.issue } : UNCERTAIN;
    case 'absent':
      return { kind: 'absent' };
    case 'rate_limited': {
      const ms = result.retryAfterMs;
      return { kind: 'rate_limited', retryAfterMs: Number.isSafeInteger(ms) && ms >= 0 ? ms : undefined };
    }
    case 'failed':
      return { kind: 'failed' };
    default:
      return UNCERTAIN;
  }
}

export function createRelayHandler(options) {
  const { ledger, github, enabled, now } = options ?? {};
  if (!ledger || typeof ledger.reserve !== 'function' || typeof ledger.complete !== 'function') {
    throw new Error('relay handler misconfigured');
  }
  if (!github || typeof github.createIssue !== 'function' || typeof github.findIssue !== 'function') {
    throw new Error('relay handler misconfigured');
  }
  if (typeof enabled !== 'boolean' && typeof enabled !== 'function') {
    throw new Error('relay handler misconfigured');
  }
  if (typeof now !== 'function') throw new Error('relay handler misconfigured');

  function isEnabled() {
    try {
      return (typeof enabled === 'function' ? enabled() : enabled) === true;
    } catch {
      return false;
    }
  }

  // Fresh time on every read; the ledger additionally applies its monotonic high-water mark.
  function clock() {
    const t = now();
    if (!Number.isSafeInteger(t) || t < 0) throw new Error('clock unavailable');
    return t;
  }

  function secondsUntil(retryAt) {
    let t;
    try {
      t = clock();
    } catch {
      return DEFAULT_RETRY_SECONDS;
    }
    return clampSeconds((retryAt - t) / 1000);
  }

  // Provider calls never throw out of here; thrown or malformed results are ambiguous.
  async function callProvider(fn) {
    try {
      return normalize(await fn());
    } catch {
      return UNCERTAIN;
    }
  }

  function record(key, outcome, issue, retryAfterMs) {
    try {
      return ledger.complete(key, outcome, clock(), issue, retryAfterMs) ?? null;
    } catch {
      return null;
    }
  }

  // Exactly the caller holding a fresh 'create' reservation reaches this function.
  async function create(key, payload, issue) {
    const result = await callProvider(() => github.createIssue(issue, payload));
    switch (result.kind) {
      case 'confirmed':
        return record(key, 'confirmed', result.issue) ? responses.created(result.issue) : responses.error();
      case 'rate_limited': {
        // Definite 429 on the create itself: no issue exists, retry needs fresh admission.
        const done = record(key, 'rate_limited', undefined, result.retryAfterMs);
        if (!done || !Number.isSafeInteger(done.retryAt)) return responses.error();
        return responses.retryLater(secondsUntil(done.retryAt));
      }
      case 'failed':
        // Auth/permission/other failure: fail closed and keep the reservation conservatively.
        record(key, 'uncertain');
        return responses.error();
      default:
        // Timeout, reset, crash-equivalent, malformed 201 or 5xx: never re-POST.
        return record(key, 'uncertain') ? responses.pending() : responses.error();
    }
  }

  // Read-only reconciliation of an in-flight or unknown reservation. Never creates.
  async function reconcile(key, payload, issueMarker) {
    const result = await callProvider(() => github.findIssue(issueMarker, payload));
    if (!isEnabled()) return responses.disabled();
    switch (result.kind) {
      case 'confirmed':
        return record(key, 'confirmed', result.issue) ? responses.duplicate(result.issue) : responses.error();
      case 'rate_limited':
        return responses.retryLater(
          result.retryAfterMs === undefined ? DEFAULT_RETRY_SECONDS : result.retryAfterMs / 1000,
        );
      case 'failed':
        return responses.error();
      default:
        // Absent or ambiguous listing never authorizes another POST.
        return responses.pending();
    }
  }

  return async function handle(request) {
    try {
      const routed = routeRequest(request);
      if (routed) return routed;
      if (!isEnabled()) return responses.disabled();

      let payload;
      try {
        payload = Object.freeze(await readPayload(request));
      } catch {
        return responses.invalid();
      }
      const rendered = renderIssue(payload);
      const issue = Object.freeze({ title: rendered.title, body: rendered.body });
      const issueMarker = marker(payload);

      // Stop switch, then a synchronous durable reservation, then the provider call with no
      // await in between: a second creator is denied before any external I/O begins.
      if (!isEnabled()) return responses.disabled();
      let decision;
      try {
        decision = ledger.reserve(payload, clock());
      } catch {
        return responses.error();
      }

      switch (decision?.action) {
        case 'duplicate':
          return isPositiveInt(decision.issue) ? responses.duplicate(decision.issue) : responses.error();
        case 'backoff':
        case 'limited':
          return Number.isSafeInteger(decision.retryAt)
            ? responses.retryLater(secondsUntil(decision.retryAt))
            : responses.error();
        case 'reconcile':
          return typeof decision.key === 'string'
            ? await reconcile(decision.key, payload, issueMarker)
            : responses.error();
        case 'create':
          return typeof decision.key === 'string'
            ? await create(decision.key, payload, issue)
            : responses.error();
        default:
          return responses.error();
      }
    } catch {
      return responses.error();
    }
  };
}
