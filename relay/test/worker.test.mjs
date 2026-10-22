// Regression tests for the real src/worker.mjs: ReportRelay storage adapter and top-level routing.
// Node cannot import 'cloudflare:workers', so the worker source is loaded with ONLY that import
// replaced by a minimal DurableObject base class, and its relative imports rewritten to absolute
// file URLs (so the real protocol/ledger/github/handler modules are used). Storage is a real
// in-memory node:sqlite database. Credentials are non-secret placeholders; no network is allowed.
import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { Ledger } from '../src/ledger.mjs';
import { FAULT_PATH } from '../src/protocol.mjs';

const WORKER_URL = new URL('../src/worker.mjs', import.meta.url);
const DO_IMPORT = "import { DurableObject } from 'cloudflare:workers';";
const DO_STUB = 'class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }';

async function loadWorker() {
  let source = readFileSync(WORKER_URL, 'utf8');
  assert.equal(source.split(DO_IMPORT).length, 2, 'expected exactly one cloudflare:workers import');
  source = source.replace(DO_IMPORT, DO_STUB);
  source = source.replace(/from '\.\/([A-Za-z0-9_-]+\.mjs)'/g, (_, name) => `from '${new URL(name, WORKER_URL).href}'`);
  assert.ok(!source.includes('cloudflare:'));
  assert.ok(!/from '\.\.?\//.test(source));
  return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
}

const worker = await loadWorker();

const SENTINEL_KEY = 'relay-initialized';
const T0 = Date.UTC(2026, 9, 8, 12, 0, 0);

const ENABLED_ENV = Object.freeze({
  REPORTING_ENABLED: 'true',
  GH_APP_ID: '1',
  GH_INSTALLATION_ID: '2',
  GH_BOT_LOGIN: 'backtube[bot]',
  GH_APP_PRIVATE_KEY: 'invalid-no-key',
});

const payload = (over = {}) => ({
  schema: 1,
  fault: 'NULL_POINTER',
  component: 'PLAYER',
  app_version_code: 1203,
  android_api: 34,
  ...over,
});
const distinct = (n) => payload({ app_version_code: 1000 + n });

const faultRequest = (body, headers = {}) =>
  new Request(`https://relay.example.invalid${FAULT_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });

let fetchCalls = 0;
let realFetch;
beforeEach(() => {
  fetchCalls = 0;
  realFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    fetchCalls += 1;
    throw new Error('network access is not allowed in tests');
  };
});
afterEach(() => {
  globalThis.fetch = realFetch;
});

// Minimal stand-in for ctx.storage: real SQL, real BEGIN/COMMIT/ROLLBACK, Map-backed kv.
function memoryStorage() {
  const db = new DatabaseSync(':memory:');
  const sql = {
    exec(query, ...bindings) {
      const stmt = db.prepare(query);
      const reads = /^\s*(select|pragma|with)\b/i.test(query) || /\breturning\b/i.test(query);
      const rows = reads ? stmt.all(...bindings) : (stmt.run(...bindings), []);
      return { toArray: () => [...rows] };
    },
  };
  const transactionSync = (callback) => {
    db.exec('BEGIN');
    try {
      const result = callback();
      db.exec('COMMIT');
      return result;
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  };
  const map = new Map();
  const kv = {
    get: (key) => map.get(key),
    put: (key, value) => void map.set(key, value),
    delete: (key) => map.delete(key),
  };
  return { db, map, storage: { sql, transactionSync, kv } };
}

// Initializes the ledger exactly as the worker does (sentinel value 1) and fills the window.
function seededStorage() {
  const mem = memoryStorage();
  const { storage } = mem;
  const ledger = new Ledger(storage.sql, (cb) => storage.transactionSync(cb), {
    get: () => storage.kv.get(SENTINEL_KEY) === 1,
    set: () => storage.kv.put(SENTINEL_KEY, 1),
  });
  for (let n = 1; n <= 3; n += 1) assert.equal(ledger.reserve(distinct(n), T0 + n).action, 'create');
  assert.equal(mem.map.get(SENTINEL_KEY), 1);
  return mem;
}

const relayTables = (db) =>
  db
    .prepare("SELECT name FROM sqlite_master WHERE name IN ('metadata', 'reports') ORDER BY name")
    .all()
    .map((row) => row.name);

test('malformed sentinel with lost tables fails closed without recreating SQL or HTTP', async () => {
  const mem = seededStorage();
  mem.db.exec('DROP TABLE reports');
  mem.db.exec('DROP TABLE metadata');
  mem.map.set(SENTINEL_KEY, 2);

  const relay = new worker.ReportRelay({ storage: mem.storage }, ENABLED_ENV);
  const response = await relay.fetch(faultRequest(distinct(9)));

  assert.equal(response.status, 503);
  assert.deepEqual(relayTables(mem.db), []);
  assert.equal(mem.map.get(SENTINEL_KEY), 2);
  assert.equal(fetchCalls, 0);
  mem.db.close();
});

test('missing sentinel with non-empty SQL ledger fails closed without HTTP', async () => {
  const mem = seededStorage();
  mem.map.delete(SENTINEL_KEY);

  const relay = new worker.ReportRelay({ storage: mem.storage }, ENABLED_ENV);
  const response = await relay.fetch(faultRequest(distinct(9)));

  assert.equal(response.status, 503);
  assert.equal(mem.db.prepare('SELECT COUNT(*) AS n FROM reports').get().n, 3);
  assert.equal(mem.map.has(SENTINEL_KEY), false);
  assert.equal(fetchCalls, 0);
  mem.db.close();
});

function mockNamespace() {
  const calls = { idFromName: [], get: [], forwarded: [] };
  const namespace = {
    idFromName(name) {
      calls.idFromName.push(name);
      return { name };
    },
    get(id) {
      calls.get.push(id);
      return {
        async fetch(request) {
          calls.forwarded.push({
            url: request.url,
            method: request.method,
            headers: [...request.headers.entries()],
            body: await request.text(),
          });
          return new Response(JSON.stringify({ status: 'accepted' }), {
            status: 202,
            headers: { 'content-type': 'application/json' },
          });
        },
      };
    },
  };
  return { namespace, calls };
}

test('disabled top-level Worker never touches the Durable Object or network', async () => {
  const { namespace, calls } = mockNamespace();
  for (const env of [{ RELAY: namespace }, { ...ENABLED_ENV, REPORTING_ENABLED: 'false', RELAY: namespace }]) {
    const response = await worker.default.fetch(faultRequest(payload()), env);
    assert.equal(response.status, 503);
  }
  assert.deepEqual(calls, { idFromName: [], get: [], forwarded: [] });
  assert.equal(fetchCalls, 0);
});

test('enabled top-level Worker routes all payloads to one global object and drops client headers', async () => {
  const { namespace, calls } = mockNamespace();
  const env = { ...ENABLED_ENV, RELAY: namespace };
  const clientHeaders = {
    authorization: 'Bearer dummy-not-a-token',
    'user-agent': 'dummy-client/0',
    'x-dummy-client': 'dummy',
  };
  const bodies = [distinct(1), distinct(2)];
  for (const body of bodies) {
    const response = await worker.default.fetch(faultRequest(body, clientHeaders), env);
    assert.equal(response.status, 202);
  }

  assert.deepEqual(calls.idFromName, ['backtube-global-v1', 'backtube-global-v1']);
  assert.deepEqual(calls.get, [{ name: 'backtube-global-v1' }, { name: 'backtube-global-v1' }]);
  assert.equal(calls.forwarded.length, 2);
  calls.forwarded.forEach((forwarded, index) => {
    assert.equal(forwarded.method, 'POST');
    assert.equal(new URL(forwarded.url).pathname, FAULT_PATH);
    assert.deepEqual(
      forwarded.headers.map(([name]) => name),
      ['content-type'],
    );
    assert.deepEqual(JSON.parse(forwarded.body), bodies[index]);
  });
  assert.equal(fetchCalls, 0);
});
