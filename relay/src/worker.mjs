// Cloudflare Worker entry point for the Backtube fault relay.
//
// - Disabled by default: unless REPORTING_ENABLED === 'true' and every GitHub App binding is
//   present, the Worker answers 503 { status: 'disabled' } without touching the Durable Object
//   or GitHub.
// - Every report from every client goes to ONE fixed Durable Object (idFromName of a constant),
//   never a per-signature object, so the global three-per-24h cap is enforced in one ledger.
// - The Worker forwards only a freshly built canonical JSON request (no client headers).
// - No cron, alarms, waitUntil/background tasks, or logging. Secrets are runtime bindings only.
import { DurableObject } from 'cloudflare:workers';
import { createRelayHandler, responses, routeRequest } from './handler.mjs';
import { Ledger } from './ledger.mjs';
import { createGitHubClient } from './github.mjs';
import { FAULT_PATH, readPayload, renderIssue, marker } from './protocol.mjs';

const RELAY_OBJECT_NAME = 'backtube-global-v1';
const INTERNAL_URL = `https://backtube-fault-relay.internal${FAULT_PATH}`;
const SENTINEL_KEY = 'relay-initialized';
const FAILED = Object.freeze({ kind: 'failed' });

function present(value) {
  return typeof value === 'string' && value.trim() !== '';
}

function credentialsConfigured(env) {
  return (
    present(env?.GH_APP_ID) &&
    present(env?.GH_INSTALLATION_ID) &&
    present(env?.GH_BOT_LOGIN) &&
    present(env?.GH_APP_PRIVATE_KEY)
  );
}

export function reportingEnabled(env) {
  try {
    return env?.REPORTING_ENABLED === 'true' && credentialsConfigured(env);
  } catch {
    return false;
  }
}

// Bridges the handler's (issue, payload)/(marker, payload) calls to the GitHub App client,
// which re-renders from the validated payload. Mismatches fail before any HTTP request.
function githubAdapter(client) {
  return Object.freeze({
    async createIssue(issue, payload) {
      let rendered;
      try {
        rendered = renderIssue(payload);
      } catch {
        return FAILED;
      }
      if (!issue || issue.title !== rendered.title || issue.body !== rendered.body) return FAILED;
      return client.createIssue(payload);
    },
    async findIssue(issueMarker, payload) {
      let expected;
      try {
        expected = marker(payload);
      } catch {
        return FAILED;
      }
      if (issueMarker !== expected) return FAILED;
      return client.findIssue(payload);
    },
  });
}

export class ReportRelay extends DurableObject {
  #ctx;
  #env;
  #handler = null;

  constructor(ctx, env) {
    super(ctx, env);
    this.#ctx = ctx;
    this.#env = env;
  }

  #getHandler() {
    if (this.#handler === null) {
      const storage = this.#ctx.storage;
      const ledger = new Ledger(storage.sql, (callback) => storage.transactionSync(callback), {
        get: () => {
          const raw = storage.kv.get(SENTINEL_KEY);
          if (raw === undefined) return false;
          if (raw === 1) return true;
          throw new Error('relay sentinel invalid');
        },
        set: () => storage.kv.put(SENTINEL_KEY, 1),
      });
      const env = this.#env;
      const client = createGitHubClient({
        appId: env?.GH_APP_ID,
        installationId: env?.GH_INSTALLATION_ID,
        botLogin: env?.GH_BOT_LOGIN,
        privateKeyPem: env?.GH_APP_PRIVATE_KEY,
        enabled: () => reportingEnabled(env),
      });
      this.#handler = createRelayHandler({
        ledger,
        github: githubAdapter(client),
        enabled: () => reportingEnabled(env),
        now: () => Date.now(),
      });
    }
    return this.#handler;
  }

  async fetch(request) {
    try {
      return await this.#getHandler()(request);
    } catch {
      return responses.error();
    }
  }
}

export default {
  async fetch(request, env) {
    try {
      const routed = routeRequest(request);
      if (routed) return routed;
      if (!reportingEnabled(env)) return responses.disabled();

      let payload;
      try {
        payload = await readPayload(request);
      } catch {
        return responses.invalid();
      }

      const namespace = env.RELAY;
      if (!namespace || typeof namespace.idFromName !== 'function' || typeof namespace.get !== 'function') {
        return responses.error();
      }
      const stub = namespace.get(namespace.idFromName(RELAY_OBJECT_NAME));
      return await stub.fetch(
        new Request(INTERNAL_URL, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(payload),
        }),
      );
    } catch {
      return responses.error();
    }
  },
};
