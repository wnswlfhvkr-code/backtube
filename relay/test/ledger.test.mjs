// RED-phase tests for src/ledger.mjs using a real node:sqlite file database.
// Only the Durable Object storage boundary (sql/transactionSync/kv) is adapted; time is passed explicitly.
// Enum values mirror app SanitizedAppFault (Fault/Component), the authoritative contract.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Ledger } from '../src/ledger.mjs';

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;
const T0 = Date.UTC(2026, 9, 8, 12, 0, 0);

const roots = [];
after(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true });
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

function cursor(rows) {
  return {
    [Symbol.iterator]: () => rows[Symbol.iterator](),
    toArray: () => [...rows],
    one() {
      if (rows.length !== 1) throw new Error('expected exactly one row');
      return rows[0];
    },
  };
}

// Mirrors ctx.storage.sql / transactionSync semantics: one statement per exec call.
function openStore(dir) {
  const db = new DatabaseSync(join(dir, 'relay.sqlite'));
  let depth = 0;
  const sql = {
    exec(query, ...bindings) {
      const stmt = db.prepare(query);
      const reads = /^\s*(select|pragma|with)\b/i.test(query) || /\breturning\b/i.test(query);
      if (reads) return cursor(stmt.all(...bindings));
      stmt.run(...bindings);
      return cursor([]);
    },
  };
  const transactionSync = (callback) => {
    const savepoint = `sp${depth}`;
    db.exec(depth === 0 ? 'BEGIN IMMEDIATE' : `SAVEPOINT ${savepoint}`);
    depth += 1;
    try {
      const result = callback();
      depth -= 1;
      db.exec(depth === 0 ? 'COMMIT' : `RELEASE ${savepoint}`);
      return result;
    } catch (error) {
      depth -= 1;
      db.exec(depth === 0 ? 'ROLLBACK' : `ROLLBACK TO ${savepoint}; RELEASE ${savepoint}`);
      throw error;
    }
  };
  // Separate durable store standing in for Durable Object KV storage.
  const marker = join(dir, 'kv-initialized');
  const kvSentinel = { get: () => existsSync(marker), set: () => writeFileSync(marker, '1') };
  return {
    db,
    kvSentinel,
    ledger: () => new Ledger(sql, transactionSync, kvSentinel),
    close: () => db.close(),
  };
}

function freshStore() {
  const dir = mkdtempSync(join(tmpdir(), 'relay-ledger-'));
  roots.push(dir);
  return { dir, store: openStore(dir) };
}

const relayTables = (db) =>
  db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('metadata', 'reports') ORDER BY name")
    .all()
    .map((row) => row.name);

test('first reservation creates; concurrent same tuple only reconciles and uses no quota', () => {
  const { store } = freshStore();
  const ledger = store.ledger();
  const first = ledger.reserve(payload(), T0);
  assert.equal(first.action, 'create');
  assert.equal(typeof first.key, 'string');
  assert.equal(store.kvSentinel.get(), true);
  for (let i = 1; i <= 5; i += 1) {
    const again = ledger.reserve(payload(), T0 + i);
    assert.equal(again.action, 'reconcile');
    assert.equal(again.key, first.key);
  }
  assert.equal(ledger.reserve(distinct(1), T0 + 10).action, 'create');
  assert.equal(ledger.reserve(distinct(2), T0 + 11).action, 'create');
  assert.equal(ledger.reserve(distinct(3), T0 + 12).action, 'limited');
  store.close();
});

test('confirmed issue persists across reopen and acknowledges as duplicate', () => {
  const { dir, store } = freshStore();
  const { key } = store.ledger().reserve(payload(), T0);
  store.ledger().complete(key, 'confirmed', T0 + 1, 42);
  store.close();

  const reopened = openStore(dir);
  const result = reopened.ledger().reserve(payload(), T0 + HOUR);
  assert.equal(result.action, 'duplicate');
  assert.equal(result.issue, 42);
  assert.equal(result.key, key);
  reopened.close();
});

test('confirmation requires a positive issue number and leaves reservation pending otherwise', () => {
  const { store } = freshStore();
  const ledger = store.ledger();
  const { key } = ledger.reserve(payload(), T0);
  for (const bad of [0, -1, 1.5, Number.NaN]) {
    assert.throws(() => ledger.complete(key, 'confirmed', T0 + 1, bad));
  }
  assert.equal(ledger.reserve(payload(), T0 + 2).action, 'reconcile');
  store.close();
});

test('uncertain create outcome never yields another create, even after reopen and long delay', () => {
  const { dir, store } = freshStore();
  const { key } = store.ledger().reserve(payload(), T0);
  store.ledger().complete(key, 'uncertain', T0 + 1);
  assert.equal(store.ledger().reserve(payload(), T0 + 60_000).action, 'reconcile');
  store.close();

  const reopened = openStore(dir);
  const ledger = reopened.ledger();
  assert.equal(ledger.reserve(payload(), T0 + 30 * DAY).action, 'reconcile');
  ledger.complete(key, 'confirmed', T0 + 30 * DAY + 1, 7);
  assert.deepEqual(
    { action: ledger.reserve(payload(), T0 + 31 * DAY).action, issue: 7 },
    { action: 'duplicate', issue: 7 },
  );
  reopened.close();
});

test('global maximum of three new creates in a rolling 24h window, persisted and not reset at midnight', () => {
  const lateEvening = Date.UTC(2026, 9, 8, 23, 0, 0);
  const { dir, store } = freshStore();
  const ledger = store.ledger();
  for (let n = 1; n <= 3; n += 1) {
    const reserved = ledger.reserve(distinct(n), lateEvening + n);
    assert.equal(reserved.action, 'create');
    ledger.complete(reserved.key, 'confirmed', lateEvening + n, n);
  }
  assert.equal(ledger.reserve(distinct(4), lateEvening + 10).action, 'limited');
  store.close();

  const reopened = openStore(dir);
  const again = reopened.ledger();
  assert.equal(again.reserve(distinct(4), lateEvening + 90 * 60 * 1000).action, 'limited');
  assert.equal(again.reserve(distinct(5), lateEvening + DAY - 1).action, 'limited');
  assert.equal(again.reserve(distinct(4), lateEvening + DAY + 1).action, 'create');
  reopened.close();
});

test('clock rollback cannot reopen quota', () => {
  const { dir, store } = freshStore();
  const ledger = store.ledger();
  for (let n = 1; n <= 3; n += 1) ledger.reserve(distinct(n), T0 + n);
  assert.equal(ledger.reserve(distinct(4), T0 - 2 * DAY).action, 'limited');
  store.close();

  const reopened = openStore(dir);
  assert.equal(reopened.ledger().reserve(distinct(4), T0 - 30 * DAY).action, 'limited');
  reopened.close();
});

test('rate-limited retry needs a fresh slot in the current window; old reservations never allow six creates', () => {
  const { store } = freshStore();
  const ledger = store.ledger();
  const keys = [1, 2, 3].map((n) => ledger.reserve(distinct(n), T0 + n).key);
  for (const key of keys) ledger.complete(key, 'rate_limited', T0 + 10, 0, HOUR);

  const waiting = ledger.reserve(distinct(1), T0 + 20 * 60 * 1000);
  assert.equal(waiting.action, 'backoff');
  assert.equal(waiting.retryAt, T0 + 10 + HOUR);

  assert.equal(ledger.reserve(distinct(1), T0 + 2 * HOUR).action, 'limited');

  let creates = 0;
  const later = T0 + DAY + HOUR;
  for (const n of [1, 2, 3, 4, 5, 6]) {
    if (ledger.reserve(distinct(n), later + n).action === 'create') creates += 1;
  }
  assert.equal(creates, 3);
  store.close();
});

test('Retry-After backoff is clamped to 24 hours', () => {
  const { store } = freshStore();
  const ledger = store.ledger();
  const { key } = ledger.reserve(payload(), T0);
  ledger.complete(key, 'rate_limited', T0, 0, 10 * DAY);
  const result = ledger.reserve(payload(), T0 + 1);
  assert.equal(result.action, 'backoff');
  assert.ok(result.retryAt > T0 && result.retryAt <= T0 + DAY);
  store.close();
});

function damageAndReopen(statements) {
  const { dir, store } = freshStore();
  assert.equal(store.ledger().reserve(payload(), T0).action, 'create');
  for (const query of statements) store.db.exec(query);
  store.close();
  const reopened = openStore(dir);
  assert.equal(reopened.kvSentinel.get(), true);
  assert.throws(() => reopened.ledger().reserve(distinct(9), T0 + HOUR));
  return reopened;
}

test('fails closed without reinitializing when the reports table is lost', () => {
  const store = damageAndReopen(['DROP TABLE reports']);
  assert.deepEqual(relayTables(store.db), ['metadata']);
  store.close();
});

test('fails closed without reinitializing when both tables are lost after initialization', () => {
  const store = damageAndReopen(['DROP TABLE reports', 'DROP TABLE metadata']);
  assert.deepEqual(relayTables(store.db), []);
  store.close();
});

test('fails closed on a malformed schema', () => {
  const store = damageAndReopen(['DROP TABLE reports', 'CREATE TABLE reports (x TEXT)']);
  const columns = store.db.prepare('PRAGMA table_info(reports)').all().map((row) => row.name);
  assert.deepEqual(columns, ['x']);
  store.close();
});

test('late confirmations keep holding window capacity from confirmation time', () => {
  const { store } = freshStore();
  const ledger = store.ledger();
  const keys = [1, 2, 3].map((n) => {
    const result = ledger.reserve(distinct(n), T0);
    assert.equal(result.action, 'create');
    return result.key;
  });
  keys.forEach((key, index) => ledger.complete(key, 'confirmed', T0 + DAY - 1000, index + 1));

  for (const n of [4, 5, 6]) {
    assert.equal(ledger.reserve(distinct(n), T0 + DAY + n).action, 'limited');
  }
  store.close();
});

test('unresolved in-flight and uncertain reservations hold capacity until resolved', () => {
  const { dir, store } = freshStore();
  const ledger = store.ledger();
  const keys = [1, 2, 3].map((n) => ledger.reserve(distinct(n), T0).key);
  ledger.complete(keys[0], 'uncertain', T0 + 1);
  ledger.complete(keys[2], 'uncertain', T0 + 1);
  assert.equal(ledger.reserve(distinct(4), T0 + 30 * DAY).action, 'limited');
  store.close();

  const reopened = openStore(dir);
  const again = reopened.ledger();
  assert.equal(again.reserve(distinct(4), T0 + 30 * DAY + 1).action, 'limited');
  keys.forEach((key, index) => again.complete(key, 'confirmed', T0 + 30 * DAY + 2, index + 1));
  assert.equal(again.reserve(distinct(4), T0 + 31 * DAY).action, 'limited');
  assert.equal(again.reserve(distinct(4), T0 + 31 * DAY + 3).action, 'create');
  reopened.close();
});

test('fails closed on a corrupted reservation time outside the window without inserting', () => {
  const { store } = freshStore();
  const ledger = store.ledger();
  for (let n = 1; n <= 3; n += 1) assert.equal(ledger.reserve(distinct(n), T0 + n).action, 'create');
  store.db.exec('PRAGMA ignore_check_constraints = ON');
  store.db.exec('UPDATE reports SET reserved_at = -1 WHERE id = 1');
  store.db.exec('PRAGMA ignore_check_constraints = OFF');

  assert.throws(() => ledger.reserve(distinct(4), T0 + HOUR), { name: 'LedgerError' });
  assert.equal(store.db.prepare('SELECT COUNT(*) AS n FROM reports').get().n, 3);
  store.close();
});
