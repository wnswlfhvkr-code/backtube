// Durable reservation ledger for the singleton relay Durable Object.
// Storage boundary: ctx.storage.sql (exec with bound parameters), transactionSync, and an
// independent KV initialization sentinel ({ get(), set() }). Time is always passed in.
//
// Tables (exactly two):
//   metadata - singleton row: schema version and monotonic time high-water mark.
//   reports  - one row per create reservation (attempt). Rows are never deleted, so every
//              reservation keeps counting toward the rolling global window, including
//              attempts that ended in a definite GitHub 429.
import { validatePayload, signature } from './protocol.mjs';

export const DAY_MS = 24 * 60 * 60 * 1000;
export const MAX_NEW_ISSUES_PER_WINDOW = 3;
export const MAX_TIME = Math.floor(Number.MAX_SAFE_INTEGER / 2);
const MIN_RETRY_MS = 60 * 1000;
const DEFAULT_RETRY_MS = 60 * 60 * 1000;
const MAX_KEY_LENGTH = 200;

const STATES = Object.freeze(['in_flight', 'uncertain', 'rate_limited', 'confirmed']);
const OUTCOMES = Object.freeze(['confirmed', 'uncertain', 'rate_limited']);

const METADATA_SQL =
  'CREATE TABLE metadata (' +
  'id INTEGER PRIMARY KEY CHECK (id = 1), ' +
  'schema_version INTEGER NOT NULL CHECK (schema_version = 1), ' +
  `high_water INTEGER NOT NULL CHECK (high_water BETWEEN 0 AND ${MAX_TIME}))`;

const REPORTS_SQL =
  'CREATE TABLE reports (' +
  'id INTEGER PRIMARY KEY, ' +
  'key TEXT NOT NULL UNIQUE, ' +
  'signature TEXT NOT NULL, ' +
  'reserved_at INTEGER NOT NULL CHECK (reserved_at >= 0), ' +
  "state TEXT NOT NULL CHECK (state IN ('in_flight', 'uncertain', 'rate_limited', 'confirmed')), " +
  'issue INTEGER CHECK (issue IS NULL OR issue > 0), ' +
  'retry_at INTEGER CHECK (retry_at IS NULL OR retry_at >= 0), ' +
  "CHECK ((state = 'confirmed') = (issue IS NOT NULL)), " +
  "CHECK ((state = 'rate_limited') = (retry_at IS NOT NULL)))";

const EXPECTED_OBJECTS = Object.freeze([
  { type: 'table', name: 'metadata', tbl_name: 'metadata', sql: METADATA_SQL },
  { type: 'table', name: 'reports', tbl_name: 'reports', sql: REPORTS_SQL },
  { type: 'index', name: 'sqlite_autoindex_reports_1', tbl_name: 'reports', sql: null },
]);

// [name, type, notnull, pk]
const EXPECTED_COLUMNS = Object.freeze({
  metadata: [
    ['id', 'INTEGER', 0, 1],
    ['schema_version', 'INTEGER', 1, 0],
    ['high_water', 'INTEGER', 1, 0],
  ],
  reports: [
    ['id', 'INTEGER', 0, 1],
    ['key', 'TEXT', 1, 0],
    ['signature', 'TEXT', 1, 0],
    ['reserved_at', 'INTEGER', 1, 0],
    ['state', 'TEXT', 1, 0],
    ['issue', 'INTEGER', 0, 0],
    ['retry_at', 'INTEGER', 0, 0],
  ],
});

// Fixed literal statements: table names are constants, never caller input.
const TABLE_INFO_SQL = Object.freeze({
  metadata: 'PRAGMA table_info(metadata)',
  reports: 'PRAGMA table_info(reports)',
});

export class LedgerError extends Error {
  constructor(message = 'relay ledger unavailable') {
    super(message);
    this.name = 'LedgerError';
  }
}

const unavailable = () => new LedgerError('relay ledger unavailable');
const invalid = () => new LedgerError('invalid ledger request');

function checkTime(now) {
  if (!Number.isSafeInteger(now) || now < 0 || now > MAX_TIME) throw invalid();
  return now;
}

function retryDelay(retryAfterMs) {
  if (!Number.isSafeInteger(retryAfterMs) || retryAfterMs < 0) return DEFAULT_RETRY_MS;
  return Math.min(Math.max(retryAfterMs, MIN_RETRY_MS), DAY_MS);
}

const POSITIVE_INT = /^[1-9][0-9]*$/;

function positiveIndex(text) {
  if (typeof text !== 'string' || !POSITIVE_INT.test(text)) return false;
  return Number.isSafeInteger(Number(text));
}

// Accepts only the exact canonical signature produced by protocol.signature().
function isCanonicalSignature(sig) {
  if (typeof sig !== 'string' || sig.length === 0 || sig.length > MAX_KEY_LENGTH) return false;
  const parts = sig.split('/');
  if (parts.length !== 6 || parts[0] !== 'backtube-fault' || parts[1] !== 'v1') return false;
  if (!positiveIndex(parts[4]) || !positiveIndex(parts[5])) return false;
  const p = validatePayload({
    schema: 1,
    fault: parts[2],
    component: parts[3],
    app_version_code: Number(parts[4]),
    android_api: Number(parts[5]),
  });
  if (p === null) return false;
  try {
    return signature(p) === sig;
  } catch {
    return false;
  }
}

// Full validation of one persisted reservation against the metadata high-water mark.
function validRow(row, highWater) {
  if (!row || !Number.isSafeInteger(row.id) || row.id <= 0) return false;
  if (!isCanonicalSignature(row.signature)) return false;
  if (typeof row.key !== 'string' || row.key.length > MAX_KEY_LENGTH) return false;
  const prefix = `${row.signature}#`;
  if (!row.key.startsWith(prefix) || !positiveIndex(row.key.slice(prefix.length))) return false;
  if (!Number.isSafeInteger(row.reserved_at) || row.reserved_at < 0 || row.reserved_at > highWater) return false;
  if (!STATES.includes(row.state)) return false;
  if (row.state === 'confirmed') {
    if (!Number.isSafeInteger(row.issue) || row.issue <= 0) return false;
  } else if (row.issue !== null) {
    return false;
  }
  if (row.state === 'rate_limited') {
    if (
      !Number.isSafeInteger(row.retry_at) ||
      row.retry_at < row.reserved_at + MIN_RETRY_MS ||
      row.retry_at > highWater + DAY_MS
    ) {
      return false;
    }
  } else if (row.retry_at !== null) {
    return false;
  }
  return true;
}

function checkRow(row) {
  if (
    !row ||
    !Number.isSafeInteger(row.id) ||
    typeof row.key !== 'string' ||
    typeof row.signature !== 'string' ||
    !Number.isSafeInteger(row.reserved_at) ||
    !STATES.includes(row.state) ||
    (row.state === 'confirmed') !== (Number.isSafeInteger(row.issue) && row.issue > 0) ||
    (row.state === 'rate_limited') !== Number.isSafeInteger(row.retry_at)
  ) {
    throw unavailable();
  }
  return row;
}

export class Ledger {
  #sql;
  #transactionSync;
  #kv;

  constructor(sql, transactionSync, kvSentinel) {
    if (!sql || typeof sql.exec !== 'function') throw unavailable();
    if (typeof transactionSync !== 'function') throw unavailable();
    if (!kvSentinel || typeof kvSentinel.get !== 'function' || typeof kvSentinel.set !== 'function') {
      throw unavailable();
    }
    this.#sql = sql;
    this.#transactionSync = transactionSync;
    this.#kv = kvSentinel;
  }

  /**
   * Atomically decides what the caller may do for this payload:
   *   duplicate - an issue is confirmed; acknowledge it.
   *   reconcile - a reservation is in flight or its outcome is unknown; lookup only, never POST.
   *   backoff   - a definite 429 is still cooling down until retryAt.
   *   limited   - the global rolling-24h cap is exhausted until retryAt.
   *   create    - a fresh reservation was durably recorded; exactly this caller may POST.
   */
  reserve(payload, now) {
    const p = validatePayload(payload);
    if (p === null) throw invalid();
    const time = checkTime(now);
    const sig = signature(p);
    return this.#run((meta) => {
      const at = this.#advance(meta, time);

      const confirmed = this.#confirmed(sig);
      if (confirmed) return { action: 'duplicate', key: confirmed.key, issue: confirmed.issue };

      const latest = this.#latest(sig);
      if (latest) {
        if (latest.state === 'in_flight' || latest.state === 'uncertain') {
          return { action: 'reconcile', key: latest.key };
        }
        if (latest.state === 'rate_limited' && at < latest.retry_at) {
          return { action: 'backoff', key: latest.key, retryAt: latest.retry_at };
        }
      }

      // Admission against the current window at the moment of the actual new POST.
      // Unresolved (in_flight/uncertain) reservations hold a slot regardless of age; confirmed
      // rows hold until their completion time + 24h; rate-limited rows until reservation + 24h.
      // Unresolved rows never free on their own, so they report a bounded retry hint only.
      const holds = [];
      for (const row of meta.rows) {
        if (row.state === 'in_flight' || row.state === 'uncertain') {
          holds.push(at + DEFAULT_RETRY_MS);
        } else if (row.reserved_at > at - DAY_MS) {
          holds.push(row.reserved_at + DAY_MS);
        }
      }
      if (holds.length >= MAX_NEW_ISSUES_PER_WINDOW) {
        holds.sort((a, b) => a - b);
        return { action: 'limited', retryAt: holds[holds.length - MAX_NEW_ISSUES_PER_WINDOW] };
      }

      const counted = this.#rows('SELECT COUNT(*) AS n FROM reports WHERE signature = ?', sig);
      const n = counted.length === 1 ? counted[0].n : NaN;
      if (!Number.isSafeInteger(n) || n < 0) throw unavailable();
      const key = `${sig}#${n + 1}`;
      this.#sql.exec(
        "INSERT INTO reports (key, signature, reserved_at, state, issue, retry_at) VALUES (?, ?, ?, 'in_flight', NULL, NULL)",
        key,
        sig,
        at,
      );
      return { action: 'create', key };
    });
  }

  /**
   * Records the outcome of a reservation:
   *   confirmed    - server-confirmed positive issue number (created or exact own-marker duplicate).
   *   uncertain    - the create outcome is unknown; the reservation stays and only lookups follow.
   *   rate_limited - a definite 429 with no issue created; retry needs a fresh window slot.
   */
  complete(key, outcome, now, issue, retryAfterMs) {
    if (typeof key !== 'string' || key.length === 0 || key.length > MAX_KEY_LENGTH) throw invalid();
    if (!OUTCOMES.includes(outcome)) throw invalid();
    const time = checkTime(now);
    if (outcome === 'confirmed' && !(Number.isSafeInteger(issue) && issue > 0)) throw invalid();

    return this.#run((meta) => {
      const at = this.#advance(meta, time);
      const found = this.#rows(
        'SELECT id, key, signature, reserved_at, state, issue, retry_at FROM reports WHERE key = ?',
        key,
      );
      if (found.length !== 1) throw invalid();
      const row = checkRow(found[0]);
      const latest = this.#latest(row.signature);

      if (outcome === 'confirmed') {
        const existing = this.#confirmed(row.signature);
        if (existing) {
          if (existing.id === row.id && existing.issue === issue) return { key, state: 'confirmed' };
          throw invalid();
        }
        if (!latest || latest.id !== row.id) throw invalid();
        // GitHub creation time is unknown: conservatively hold the slot from confirmation time.
        this.#sql.exec(
          "UPDATE reports SET state = 'confirmed', issue = ?, retry_at = NULL, reserved_at = ? WHERE id = ?",
          issue,
          Math.max(row.reserved_at, at),
          row.id,
        );
        return { key, state: 'confirmed' };
      }

      if (!latest || latest.id !== row.id) throw invalid();

      if (outcome === 'uncertain') {
        if (row.state !== 'in_flight' && row.state !== 'uncertain') throw invalid();
        this.#sql.exec("UPDATE reports SET state = 'uncertain', retry_at = NULL WHERE id = ?", row.id);
        return { key, state: 'uncertain' };
      }

      // rate_limited: only a definite 429 on the original in-flight create attempt.
      if (row.state !== 'in_flight') throw invalid();
      const retryAt = at + retryDelay(retryAfterMs);
      this.#sql.exec("UPDATE reports SET state = 'rate_limited', retry_at = ? WHERE id = ?", retryAt, row.id);
      return { key, state: 'rate_limited', retryAt };
    });
  }

  #run(operation) {
    try {
      this.#transactionSync(() => this.#prepare());
      return this.#transactionSync(() => {
        this.#verify(this.#objects());
        const meta = this.#metadata();
        const rows = this.#scan(meta.high_water);
        return operation({ ...meta, rows });
      });
    } catch (error) {
      if (error instanceof LedgerError) throw error;
      throw unavailable();
    }
  }

  #rows(query, ...bindings) {
    return this.#sql.exec(query, ...bindings).toArray();
  }

  #objects() {
    return this.#rows(
      "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE tbl_name IN ('metadata', 'reports') OR name IN ('metadata', 'reports') ORDER BY name",
    );
  }

  // Initializes a fresh object exactly once; never recreates tables after initialization.
  #prepare() {
    const objects = this.#objects();
    const initialized = Boolean(this.#kv.get());
    if (objects.length === 0) {
      if (initialized) throw unavailable();
      this.#sql.exec(METADATA_SQL);
      this.#sql.exec(REPORTS_SQL);
      this.#sql.exec('INSERT INTO metadata (id, schema_version, high_water) VALUES (1, 1, 0)');
      this.#verify(this.#objects());
      this.#metadata();
      this.#kv.set();
      return;
    }
    // Existing tables without the sentinel are never adopted: integrity evidence is missing.
    if (!initialized) throw unavailable();
    this.#verify(objects);
    this.#metadata();
  }

  #verify(objects) {
    if (!this.#kv.get() && objects.length === 0) throw unavailable();
    if (objects.length !== EXPECTED_OBJECTS.length) throw unavailable();
    EXPECTED_OBJECTS.forEach((expected, index) => {
      const actual = objects[index];
      if (
        !actual ||
        actual.type !== expected.type ||
        actual.name !== expected.name ||
        actual.tbl_name !== expected.tbl_name ||
        (actual.sql ?? null) !== expected.sql
      ) {
        throw unavailable();
      }
    });
    for (const table of ['metadata', 'reports']) {
      const columns = this.#rows(TABLE_INFO_SQL[table]);
      const expected = EXPECTED_COLUMNS[table];
      if (columns.length !== expected.length) throw unavailable();
      expected.forEach(([name, type, notnull, pk], index) => {
        const column = columns[index];
        if (
          !column ||
          column.name !== name ||
          column.type !== type ||
          Number(column.notnull) !== notnull ||
          Number(column.pk) !== pk ||
          (column.dflt_value ?? null) !== null
        ) {
          throw unavailable();
        }
      });
    }
  }

  #metadata() {
    const rows = this.#rows('SELECT id, schema_version, high_water FROM metadata');
    if (rows.length !== 1) throw unavailable();
    const meta = rows[0];
    if (
      meta.id !== 1 ||
      meta.schema_version !== 1 ||
      !Number.isSafeInteger(meta.high_water) ||
      meta.high_water < 0 ||
      meta.high_water > MAX_TIME
    ) {
      throw unavailable();
    }
    return meta;
  }

  // Validates every persisted reservation before any quota or admission decision.
  #scan(highWater) {
    const rows = this.#rows(
      'SELECT id, key, signature, reserved_at, state, issue, retry_at FROM reports ORDER BY id ASC',
    );
    const confirmedSignatures = new Set();
    for (const row of rows) {
      if (!validRow(row, highWater)) throw unavailable();
      if (row.state === 'confirmed') {
        if (confirmedSignatures.has(row.signature)) throw unavailable();
        confirmedSignatures.add(row.signature);
      }
    }
    return rows;
  }

  // Monotonic clock: rollback never moves ledger time backwards.
  #advance(meta, time) {
    const at = Math.max(time, meta.high_water);
    if (at !== meta.high_water) {
      this.#sql.exec('UPDATE metadata SET high_water = ? WHERE id = 1', at);
    }
    return at;
  }

  #confirmed(sig) {
    const rows = this.#rows(
      "SELECT id, key, signature, reserved_at, state, issue, retry_at FROM reports WHERE signature = ? AND state = 'confirmed' ORDER BY id ASC",
      sig,
    );
    if (rows.length > 1) throw unavailable();
    return rows.length === 1 ? checkRow(rows[0]) : null;
  }

  #latest(sig) {
    const rows = this.#rows(
      'SELECT id, key, signature, reserved_at, state, issue, retry_at FROM reports WHERE signature = ? ORDER BY id DESC LIMIT 1',
      sig,
    );
    return rows.length === 1 ? checkRow(rows[0]) : null;
  }
}
