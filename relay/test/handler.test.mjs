// Handler tests for the Backtube fault relay (authored first; RED until src/handler.mjs exists).
//
// Interface under test (src/handler.mjs):
//   createRelayHandler({ ledger, github, enabled, now }) -> async (Request) => Response
//     ledger  - real Ledger from src/ledger.mjs backed by an in-memory node:sqlite adapter.
//     github  - { createIssue({ title, body }), findIssue(marker) } resolving to one of
//               { kind: 'confirmed', issue } | { kind: 'absent' } |
//               { kind: 'rate_limited', retryAfterMs } | { kind: 'uncertain' } | { kind: 'failed' }
//     enabled - boolean or function; consulted immediately before every provider call.
//     now     - () => epoch ms; read fresh whenever time is needed (including at completion).
//   Response bodies are JSON with only { status, issue_number } when confirmed, otherwise only
//   { status } with status in pending/disabled/invalid/error. No raw provider data is echoed.
//
// The GitHub double is scripted; the ledger is never faked.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createRelayHandler } from '../src/handler.mjs';
import { Ledger } from '../src/ledger.mjs';
import { renderIssue, marker, ISSUE_TITLE, MAX_BODY_BYTES } from '../src/protocol.mjs';

const T0 = Date.UTC(2026, 9, 8, 12, 0, 0);
const DAY_MS = 24 * 60 * 60 * 1000;
const URL_FAULT = 'https://relay.example/v1/fault';
const SAFE_STATUSES = new Set(['created', 'duplicate', 'pending', 'disabled', 'invalid', 'error']);

const valid = Object.freeze({
  schema: 1,
  fault: 'NULL_POINTER',
  component: 'PLAYER',
  app_version_code: 42,
  android_api: 35,
});

function createStore() {
  const db = new DatabaseSync(':memory:');
  let initialized = false;
  const kv = {
    get: () => initialized,
    set: () => {
      initialized = true;
    },
  };
  const sql = {
    exec(query, ...bindings) {
      const rows = db.prepare(query).all(...bindings);
      return { toArray: () => rows };
    },
  };
  const transactionSync = (fn) => {
    db.exec('BEGIN IMMEDIATE');
    let result;
    try {
      result = fn();
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
    db.exec('COMMIT');
    return result;
  };
  return { db, kv, ledger: new Ledger(sql, transactionSync, kv) };
}

function nextResult(queue) {
  const item = queue.shift();
  if (item === undefined) return { kind: 'failed' };
  return typeof item === 'function' ? item() : item;
}

function setup({ enabled } = {}) {
  const store = createStore();
  const gate = { on: true, checked: false };
  const clock = { t: T0 };
  const github = {
    createQueue: [],
    findQueue: [],
    creates: [],
    finds: [],
    violations: 0,
    async createIssue(issue) {
      // Recorded instead of thrown so a handler catch-all cannot hide the violation.
      if (!gate.checked) github.violations += 1;
      gate.checked = false;
      github.creates.push(issue);
      return nextResult(github.createQueue);
    },
    async findIssue(issueMarker) {
      if (!gate.checked) github.violations += 1;
      gate.checked = false;
      github.finds.push(issueMarker);
      return nextResult(github.findQueue);
    },
  };
  const enabledOption =
    enabled !== undefined
      ? enabled
      : () => {
          gate.checked = gate.on;
          return gate.on;
        };
  const handler = createRelayHandler({
    ledger: store.ledger,
    github,
    enabled: enabledOption,
    now: () => clock.t,
  });
  return { ...store, gate, clock, github, handler };
}

function post(payload, headers = { 'content-type': 'application/json' }) {
  return new Request(URL_FAULT, {
    method: 'POST',
    headers,
    body: typeof payload === 'string' ? payload : JSON.stringify(payload),
  });
}

async function call(h, request) {
  const res = await h.handler(request);
  assert.ok(res instanceof Response, 'handler must return a Response');
  const text = await res.text();
  const json = JSON.parse(text);
  assert.match(res.headers.get('content-type') ?? '', /^application\/json/);
  assert.equal(typeof json, 'object');
  assert.ok(json !== null && !Array.isArray(json));
  for (const key of Object.keys(json)) {
    assert.ok(key === 'status' || key === 'issue_number', 'unexpected response key');
  }
  assert.ok(SAFE_STATUSES.has(json.status), 'unexpected response status');
  if ('issue_number' in json) {
    assert.ok(json.status === 'created' || json.status === 'duplicate');
    assert.ok(Number.isSafeInteger(json.issue_number) && json.issue_number > 0);
  }
  return { status: res.status, json, text, headers: res.headers };
}

function reportCount(db) {
  const exists = db
    .prepare("SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table' AND name = 'reports'")
    .get().n;
  if (!exists) return 0;
  return db.prepare('SELECT COUNT(*) AS n FROM reports').get().n;
}

function tableNames(db) {
  return db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    .all()
    .map((row) => row.name);
}

function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

test('disabled relay answers before any ledger or GitHub work', async () => {
  const off = setup({ enabled: false });
  const r1 = await call(off, post(valid));
  assert.equal(r1.status, 503);
  assert.deepEqual(r1.json, { status: 'disabled' });
  assert.equal(off.github.creates.length, 0);
  assert.equal(off.github.finds.length, 0);
  assert.equal(reportCount(off.db), 0);

  const h = setup();
  h.gate.on = false;
  const r2 = await call(h, post(valid));
  assert.equal(r2.status, 503);
  assert.deepEqual(r2.json, { status: 'disabled' });
  assert.equal(h.github.creates.length + h.github.finds.length, 0);
  assert.equal(reportCount(h.db), 0);
  assert.equal(h.github.violations, 0);
});

test('invalid, extra-field, non-JSON and oversize requests are rejected without GitHub', async () => {
  const h = setup();
  const secret = 'person@example.com';
  const big = JSON.stringify({ ...valid, pad: 'x'.repeat(MAX_BODY_BYTES) });
  assert.ok(new TextEncoder().encode(big).byteLength > MAX_BODY_BYTES);

  const streamed = () => {
    const bytes = new TextEncoder().encode(big);
    let offset = 0;
    const stream = new ReadableStream({
      pull(controller) {
        if (offset >= bytes.byteLength) {
          controller.close();
          return;
        }
        controller.enqueue(bytes.slice(offset, offset + 64));
        offset += 64;
      },
    });
    return new Request(URL_FAULT, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: stream,
      duplex: 'half',
    });
  };

  const requests = [
    post({ ...valid, note: secret }),
    post({ ...valid, fault: secret }),
    post({ ...valid, android_api: 22 }),
    post({ schema: 1, fault: 'NULL_POINTER', component: 'PLAYER', app_version_code: 42 }),
    post('{not json'),
    post(valid, { 'content-type': 'text/plain' }),
    post(valid, { 'content-type': 'application/json', 'content-length': '513' }),
    post(big),
    streamed(),
  ];
  for (const request of requests) {
    const r = await call(h, request);
    assert.equal(r.status, 400);
    assert.deepEqual(r.json, { status: 'invalid' });
    assert.ok(!r.text.includes(secret), 'response must not echo request input');
  }
  assert.equal(h.github.creates.length, 0);
  assert.equal(h.github.finds.length, 0);
  assert.equal(reportCount(h.db), 0);
});

test('only POST /v1/fault is routed; other paths 404 and other methods 405', async () => {
  const h = setup();
  const notAllowed = await call(h, new Request(URL_FAULT, { method: 'GET' }));
  assert.equal(notAllowed.status, 405);
  assert.match(notAllowed.headers.get('allow') ?? '', /POST/);

  const wrongGet = await call(h, new Request('https://relay.example/v1/other', { method: 'GET' }));
  assert.equal(wrongGet.status, 404);
  const wrongPost = await call(
    h,
    new Request('https://relay.example/v1/faults', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(valid),
    }),
  );
  assert.equal(wrongPost.status, 404);
  assert.equal(h.github.creates.length + h.github.finds.length, 0);
  assert.equal(reportCount(h.db), 0);
});

test('first report creates exactly one issue; repeat is acknowledged as duplicate', async () => {
  const h = setup();
  h.github.createQueue.push({ kind: 'confirmed', issue: 42 });

  const first = await call(h, post(valid));
  assert.equal(first.status, 201);
  assert.deepEqual(first.json, { status: 'created', issue_number: 42 });
  assert.equal(h.github.creates.length, 1);
  const rendered = renderIssue(valid);
  assert.equal(h.github.creates[0].title, ISSUE_TITLE);
  assert.equal(h.github.creates[0].body, rendered.body);

  const again = await call(h, post(valid));
  assert.equal(again.status, 200);
  assert.deepEqual(again.json, { status: 'duplicate', issue_number: 42 });
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.finds.length, 0);
  assert.equal(h.github.violations, 0);
});

test('unknown create outcome stays pending and is only reconciled by lookup, never re-POSTed', async () => {
  const h = setup();
  h.github.createQueue.push({ kind: 'uncertain' });
  const first = await call(h, post(valid));
  assert.equal(first.status, 202);
  assert.deepEqual(first.json, { status: 'pending' });

  h.github.findQueue.push({ kind: 'absent' });
  h.clock.t += DAY_MS * 2; // Even long after, an absent listing never authorizes a new POST.
  const second = await call(h, post(valid));
  assert.equal(second.status, 202);
  assert.deepEqual(second.json, { status: 'pending' });
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.finds.length, 1);
  assert.equal(h.github.finds[0], marker(valid));

  h.github.findQueue.push({ kind: 'confirmed', issue: 77 });
  const third = await call(h, post(valid));
  assert.equal(third.status, 200);
  assert.deepEqual(third.json, { status: 'duplicate', issue_number: 77 });
  assert.equal(h.github.creates.length, 1);

  // Ledger acknowledged the reconciled issue; no further provider I/O is needed.
  const fourth = await call(h, post(valid));
  assert.deepEqual(fourth.json, { status: 'duplicate', issue_number: 77 });
  assert.equal(h.github.finds.length, 2);
  assert.equal(h.github.creates.length, 1);
  const decision = h.ledger.reserve(valid, h.clock.t);
  assert.equal(decision.action, 'duplicate');
  assert.equal(decision.issue, 77);
  assert.equal(h.github.violations, 0);
});

test('thrown or malformed provider results are ambiguous: pending, no raw text, no repeat POST', async () => {
  const h = setup();
  const leak = 'ghs_SECRET_TOKEN raw-github-body';
  h.github.createQueue.push(() => {
    throw new Error(leak);
  });
  const first = await call(h, post(valid));
  assert.equal(first.status, 202);
  assert.deepEqual(first.json, { status: 'pending' });
  assert.ok(!first.text.includes('SECRET'));

  h.github.findQueue.push(() => {
    throw new Error(leak);
  });
  const second = await call(h, post(valid));
  assert.equal(second.status, 202);
  assert.ok(!second.text.includes('SECRET'));

  h.github.findQueue.push({ kind: 'confirmed', issue: -3 });
  const third = await call(h, post(valid));
  assert.equal(third.status, 202);
  assert.deepEqual(third.json, { status: 'pending' });

  const other = { ...valid, fault: 'ILLEGAL_STATE' };
  h.github.createQueue.push({ kind: 'confirmed', issue: 0 });
  const malformed = await call(h, post(other));
  assert.equal(malformed.status, 202);
  h.github.findQueue.push({ kind: 'absent' });
  await call(h, post(other));

  assert.equal(h.github.creates.length, 2);
  assert.equal(h.github.finds.length, 3);
  assert.equal(h.github.violations, 0);
});

test('concurrent duplicate while create is in flight only reads, never creates', async () => {
  const h = setup();
  const gate = deferred();
  h.github.createQueue.push(() => gate.promise);
  const firstPromise = h.handler(post(valid));
  // Let the first request reach the awaited create.
  for (let i = 0; i < 20 && h.github.creates.length === 0; i += 1) {
    await new Promise((r) => setImmediate(r));
  }
  assert.equal(h.github.creates.length, 1);

  h.github.findQueue.push({ kind: 'absent' });
  const concurrent = await call(h, post(valid));
  assert.equal(concurrent.status, 202);
  assert.deepEqual(concurrent.json, { status: 'pending' });
  assert.equal(h.github.creates.length, 1);

  gate.resolve({ kind: 'confirmed', issue: 11 });
  const res = await firstPromise;
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), { status: 'created', issue_number: 11 });
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.violations, 0);
});

test('GitHub 429 returns 429 with Retry-After seconds and no provider I/O until the deadline', async () => {
  const h = setup();
  h.github.createQueue.push({ kind: 'rate_limited', retryAfterMs: 120000 });
  const first = await call(h, post(valid));
  assert.equal(first.status, 429);
  assert.deepEqual(first.json, { status: 'pending' });
  assert.equal(first.headers.get('retry-after'), '120');

  h.clock.t = T0 + 30000;
  const waiting = await call(h, post(valid));
  assert.equal(waiting.status, 429);
  assert.equal(waiting.headers.get('retry-after'), '90');
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.finds.length, 0);

  h.clock.t = T0 + 120000;
  h.github.createQueue.push({ kind: 'confirmed', issue: 5 });
  const retried = await call(h, post(valid));
  assert.equal(retried.status, 201);
  assert.deepEqual(retried.json, { status: 'created', issue_number: 5 });
  assert.equal(h.github.creates.length, 2);
  assert.equal(h.github.violations, 0);
});

test('global cap: three new issues per rolling 24h across signatures; fourth gets 429 without GitHub', async () => {
  const h = setup();
  const faults = ['NULL_POINTER', 'ILLEGAL_STATE', 'INDEX_BOUNDS'];
  for (let i = 0; i < faults.length; i += 1) {
    h.github.createQueue.push({ kind: 'confirmed', issue: i + 1 });
    const r = await call(h, post({ ...valid, fault: faults[i] }));
    assert.equal(r.status, 201);
    assert.deepEqual(r.json, { status: 'created', issue_number: i + 1 });
  }

  const fourthPayload = { ...valid, fault: 'ASSERTION' };
  const fourth = await call(h, post(fourthPayload));
  assert.equal(fourth.status, 429);
  assert.deepEqual(fourth.json, { status: 'pending' });
  assert.equal(fourth.headers.get('retry-after'), String(DAY_MS / 1000));
  const otherComponent = await call(h, post({ ...valid, component: 'UI' }));
  assert.equal(otherComponent.status, 429);
  assert.equal(h.github.creates.length, 3);
  assert.equal(h.github.finds.length, 0);

  h.clock.t = T0 + DAY_MS;
  h.github.createQueue.push({ kind: 'confirmed', issue: 4 });
  const later = await call(h, post(fourthPayload));
  assert.equal(later.status, 201);
  assert.equal(h.github.creates.length, 4);
  assert.equal(h.github.violations, 0);
});

test('stop switch is consulted before each provider call and toggling mid-create blocks further I/O', async () => {
  const h = setup();
  h.github.createQueue.push(() => {
    h.gate.on = false;
    return { kind: 'confirmed', issue: 9 };
  });
  const first = await call(h, post(valid));
  assert.equal(first.status, 201);
  assert.deepEqual(first.json, { status: 'created', issue_number: 9 });

  const other = { ...valid, component: 'APP' };
  const blocked = await call(h, post(other));
  assert.equal(blocked.status, 503);
  assert.deepEqual(blocked.json, { status: 'disabled' });
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.finds.length, 0);

  h.gate.on = true;
  const dup = await call(h, post(valid));
  assert.equal(dup.status, 200);
  assert.deepEqual(dup.json, { status: 'duplicate', issue_number: 9 });
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.finds.length, 0);
  assert.equal(h.github.violations, 0);
});

test('invalid store fails closed with 503 and is never reset or followed by GitHub calls', async () => {
  const h = setup();
  h.github.createQueue.push({ kind: 'confirmed', issue: 1 });
  assert.equal((await call(h, post(valid))).status, 201);

  h.db.exec('DROP TABLE reports');
  const partial = await call(h, post({ ...valid, fault: 'ASSERTION' }));
  assert.equal(partial.status, 503);
  assert.deepEqual(partial.json, { status: 'error' });
  assert.deepEqual(tableNames(h.db), ['metadata']);

  h.db.exec('DROP TABLE metadata');
  const lost = await call(h, post({ ...valid, fault: 'ASSERTION' }));
  assert.equal(lost.status, 503);
  assert.deepEqual(lost.json, { status: 'error' });
  assert.deepEqual(tableNames(h.db), []);
  assert.equal(h.github.creates.length, 1);
  assert.equal(h.github.finds.length, 0);

  const fresh = setup();
  fresh.db.exec('CREATE TABLE metadata (id INTEGER PRIMARY KEY, junk TEXT)');
  const malformed = await call(fresh, post(valid));
  assert.equal(malformed.status, 503);
  assert.deepEqual(malformed.json, { status: 'error' });
  assert.deepEqual(tableNames(fresh.db), ['metadata']);
  assert.equal(fresh.github.creates.length + fresh.github.finds.length, 0);
});
