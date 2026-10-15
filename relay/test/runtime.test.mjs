// Local Miniflare/workerd runtime test for the relay Worker and its SQLite Durable Object.
//
// - Bundles the real src/worker.mjs in memory with esbuild (a Wrangler dependency).
// - Runs it under local workerd only. No Cloudflare or GitHub auth and no network: every
//   outbound fetch is answered locally with 503 and counted.
// - The GitHub App key binding is deliberately invalid, so no provider request can succeed.
// - Assertions cover only fixed HTTP statuses and numeric counts.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';
import { Miniflare, Log, LogLevel, convertV4MiniflareOptions } from 'miniflare';
import { FAULT_PATH, readPayload } from '../src/protocol.mjs';

const RELAY_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');
const TEST_TIMEOUT_MS = 30_000;
const ORIGIN = 'https://relay.test';

// Distinct report fixtures matching the exact src/protocol.mjs contract; only
// app_version_code varies. The local readPayload check below fails fast with a clear
// message before workerd starts.
function report(n) {
  return {
    schema: 1,
    fault: 'NULL_POINTER',
    component: 'PLAYER',
    app_version_code: n,
    android_api: 34,
  };
}

async function assertFixtureValid(payload) {
  try {
    await readPayload(
      new Request(`${ORIGIN}${FAULT_PATH}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      }),
    );
  } catch {
    assert.fail('runtime test fixture is not a valid fault report for src/protocol.mjs');
  }
}

let bundled;
async function workerScript() {
  if (bundled === undefined) {
    const result = await esbuild.build({
      entryPoints: [join(RELAY_DIR, 'src/worker.mjs')],
      bundle: true,
      format: 'esm',
      platform: 'browser',
      external: ['cloudflare:workers'],
      write: false,
      logLevel: 'silent',
    });
    assert.equal(result.outputFiles.length, 1);
    bundled = result.outputFiles[0].text;
  }
  return bundled;
}

async function startRelay({ enabled, persistDir, counter }) {
  const options = {
    // Stable worker name so the persisted Durable Object storage is the same across restarts.
    name: 'backtube-runtime-test',
    modules: true,
    script: await workerScript(),
    compatibilityDate: '2026-10-08',
    durableObjects: { RELAY: { className: 'ReportRelay', useSQLite: true } },
    bindings: {
      REPORTING_ENABLED: enabled ? 'true' : 'false',
      GH_APP_ID: '1',
      GH_INSTALLATION_ID: '2',
      GH_BOT_LOGIN: 'test[bot]',
      GH_APP_PRIVATE_KEY: 'invalid-no-key',
    },
    outboundService: () => {
      counter.outbound++;
      return new Response(null, { status: 503 });
    },
    log: new Log(LogLevel.NONE),
  };
  if (persistDir !== undefined) options.resourcePersistencePath = persistDir;
  // The installed Miniflare uses a workers-array constructor; adapt the legacy options
  // with its official exported converter.
  const mf = new Miniflare(convertV4MiniflareOptions(options));
  await mf.ready;
  return mf;
}

async function post(mf, payload) {
  const res = await mf.dispatchFetch(`${ORIGIN}${FAULT_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
  await res.arrayBuffer();
  return res.status;
}

async function disposeQuietly(mf) {
  if (!mf) return;
  try {
    await mf.dispose();
  } catch {
    // Teardown must continue so the temporary directory is still removed.
  }
}

test('default-disabled relay answers 503 with zero outbound', { timeout: TEST_TIMEOUT_MS }, async () => {
  await assertFixtureValid(report(1));
  const counter = { outbound: 0 };
  let mf;
  try {
    mf = await startRelay({ enabled: false, counter });
    assert.equal(await post(mf, report(1)), 503);
    assert.equal(await post(mf, report(2)), 503);
    assert.equal(counter.outbound, 0);
  } finally {
    await disposeQuietly(mf);
  }
});

test('SQLite ledger enforces the global cap and survives a restart', { timeout: TEST_TIMEOUT_MS }, async () => {
  for (let n = 1; n <= 5; n++) await assertFixtureValid(report(n));
  const counter = { outbound: 0 };
  const persistDir = await mkdtemp(join(tmpdir(), 'backtube-relay-runtime-'));
  let first;
  let second;
  try {
    first = await startRelay({ enabled: true, persistDir, counter });

    // Invalid key: each create fails closed with 503 but still occupies a reservation.
    assert.equal(await post(first, report(1)), 503);
    assert.equal(await post(first, report(2)), 503);
    assert.equal(await post(first, report(3)), 503);

    // A fourth distinct report exceeds the global three-per-24h cap.
    assert.equal(await post(first, report(4)), 429);

    // The same tuple again takes the read-only reconcile path; the invalid key cannot
    // perform the lookup, so the handler fails closed with 503 and never creates.
    assert.equal(await post(first, report(1)), 503);

    await first.dispose();
    first = undefined;

    second = await startRelay({ enabled: true, persistDir, counter });
    assert.equal(await post(second, report(5)), 429);

    assert.equal(counter.outbound, 0);
  } finally {
    await disposeQuietly(first);
    await disposeQuietly(second);
    await rm(persistDir, { recursive: true, force: true });
  }
});
