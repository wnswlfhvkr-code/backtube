import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify, randomBytes } from 'node:crypto';
import { createGitHubClient, renderIssue } from '../src/github.mjs';
import { renderIssue as protocolRenderIssue } from '../src/protocol.mjs';

const OWNER = 'wnswlfhvkr-code';
const REPO = 'backtube';
const FULL_NAME = `${OWNER}/${REPO}`;
const APP_ID = '424242';
const INSTALLATION_ID = '9001';
const BOT_LOGIN = 'backtube-relay[bot]';
const NOW_MS = Date.UTC(2026, 9, 8, 12, 0, 0);
const NOW_SEC = Math.floor(NOW_MS / 1000);
const TOKEN_PATH = `/app/installations/${INSTALLATION_ID}/access_tokens`;
const ISSUES_PATH = `/repos/${FULL_NAME}/issues`;

function makeKeys() {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  return { publicKey, privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
}

function syntheticToken() {
  return `synthetic-test-token-${randomBytes(12).toString('hex')}`;
}

function samplePayload() {
  return { schema: 1, fault: 'NULL_POINTER', component: 'PLAYER', app_version_code: 1203, android_api: 34 };
}

function json(status, value, headers = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

function tokenResponse(token, overrides = {}) {
  return json(201, {
    token,
    expires_at: new Date(NOW_MS + 3600_000).toISOString(),
    permissions: { issues: 'write' },
    repository_selection: 'selected',
    repositories: [{ name: REPO, full_name: FULL_NAME }],
    ...overrides,
  });
}

async function normalize(input, init = {}) {
  if (input instanceof Request) {
    const text = await input.text();
    return { url: new URL(input.url), method: input.method.toUpperCase(), headers: input.headers, body: text };
  }
  const url = new URL(String(input));
  const method = String(init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers ?? {});
  const body = init.body == null ? '' : String(init.body);
  return { url, method, headers, body };
}

// Records are kept in memory only and never passed to assertion messages.
function makeFakeFetch(routes) {
  const calls = [];
  const fetch = async (input, init) => {
    const req = await normalize(input, init);
    calls.push(req);
    if (req.url.origin !== 'https://api.github.com') return json(404, { message: 'Not Found' });
    const key = `${req.method} ${req.url.pathname}`;
    const handler = routes[key];
    if (!handler) return json(404, { message: 'Not Found' });
    return handler(req);
  };
  const count = (method, path) => calls.filter((c) => c.method === method && c.url.pathname === path).length;
  return { fetch, calls, count };
}

function makeClient(fetch, privateKeyPem) {
  return createGitHubClient({
    appId: APP_ID,
    installationId: INSTALLATION_ID,
    privateKeyPem,
    botLogin: BOT_LOGIN,
    fetch,
    now: () => NOW_MS,
  });
}

function decodeSegment(segment) {
  return JSON.parse(Buffer.from(segment, 'base64url').toString('utf8'));
}

test('app JWT is RS256-signed with safe claims and installation token is restricted', async () => {
  const { publicKey, privateKeyPem } = makeKeys();
  const token = syntheticToken();
  const fake = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(token),
    [`POST ${ISSUES_PATH}`]: () => json(201, { number: 7 }),
  });
  const client = makeClient(fake.fetch, privateKeyPem);

  const result = await client.createIssue(samplePayload());
  assert.equal(result.kind, 'confirmed');
  assert.equal(result.issue, 7);

  const tokenCall = fake.calls.find((c) => c.method === 'POST' && c.url.pathname === TOKEN_PATH);
  assert.ok(tokenCall !== undefined, 'installation token request was made');

  const auth = tokenCall.headers.get('authorization') ?? '';
  assert.ok(auth.startsWith('Bearer '), 'token request uses bearer scheme');
  const parts = auth.slice('Bearer '.length).split('.');
  assert.equal(parts.length, 3);

  const header = decodeSegment(parts[0]);
  assert.equal(header.alg, 'RS256');

  const signatureValid = verify(
    'sha256',
    Buffer.from(`${parts[0]}.${parts[1]}`),
    publicKey,
    Buffer.from(parts[2], 'base64url'),
  );
  assert.equal(signatureValid, true);

  const claims = decodeSegment(parts[1]);
  assert.equal(String(claims.iss) === APP_ID, true);
  assert.equal(typeof claims.iat, 'number');
  assert.equal(typeof claims.exp, 'number');
  assert.equal(claims.iat, NOW_SEC - 60);
  assert.equal(claims.exp > NOW_SEC, true);
  assert.equal(claims.exp - claims.iat <= 600, true);
  assert.equal(claims.exp <= NOW_SEC + 600, true);
  assert.equal(claims.iat <= NOW_SEC, true);

  assert.deepEqual(JSON.parse(tokenCall.body), {
    repositories: [REPO],
    permissions: { issues: 'write' },
  });

  const issueCall = fake.calls.find((c) => c.method === 'POST' && c.url.pathname === ISSUES_PATH);
  assert.ok(issueCall !== undefined, 'issue request was made');
  const issueAuth = issueCall.headers.get('authorization') ?? '';
  const usesInstallationToken = issueAuth === `Bearer ${token}` || issueAuth === `token ${token}`;
  assert.equal(usesInstallationToken, true);
});

test('no issue request when installation token is scoped to wrong repository or permissions', async () => {
  const { privateKeyPem } = makeKeys();
  const badResponses = [
    { repositories: [{ name: REPO, full_name: 'someone-else/backtube' }] },
    { repositories: [{ name: REPO, full_name: FULL_NAME }, { name: 'other', full_name: `${OWNER}/other` }] },
    { permissions: { issues: 'read' } },
    { permissions: { issues: 'write', contents: 'write' } },
  ];

  for (const overrides of badResponses) {
    const fake = makeFakeFetch({
      [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken(), overrides),
      [`POST ${ISSUES_PATH}`]: () => json(201, { number: 1 }),
      [`GET ${ISSUES_PATH}`]: () => json(200, []),
    });
    const client = makeClient(fake.fetch, privateKeyPem);

    const created = await client.createIssue(samplePayload());
    assert.equal(created.kind, 'failed');
    const found = await client.findIssue(samplePayload());
    assert.equal(found.kind, 'failed');

    assert.equal(fake.count('POST', ISSUES_PATH), 0);
    assert.equal(fake.count('GET', ISSUES_PATH), 0);
  }
});

test('createIssue posts only the fixed rendered issue for the fixed repository', async () => {
  const { privateKeyPem } = makeKeys();
  const fake = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`POST ${ISSUES_PATH}`]: () => json(201, { number: 12 }),
  });
  const client = makeClient(fake.fetch, privateKeyPem);
  const payload = { ...samplePayload(), component: 'UI' };

  const result = await client.createIssue(payload);
  assert.equal(result.kind, 'confirmed');
  assert.equal(result.issue, 12);

  const posts = fake.calls.filter((c) => c.method === 'POST' && c.url.pathname === ISSUES_PATH);
  assert.equal(posts.length, 1);
  assert.equal(renderIssue, protocolRenderIssue);
  const rendered = renderIssue(payload);
  assert.deepEqual(JSON.parse(posts[0].body), { title: rendered.title, body: rendered.body });

  const otherHosts = fake.calls.filter((c) => c.url.origin !== 'https://api.github.com');
  assert.equal(otherHosts.length, 0);

  const invalid = await client.createIssue({ ...samplePayload(), fault: 'NOT_A_REAL_TYPE' });
  assert.equal(invalid.kind, 'failed');
  const invalidComponent = await client.createIssue({ ...samplePayload(), component: 'SERVER' });
  assert.equal(invalidComponent.kind, 'failed');
  assert.equal(fake.count('POST', ISSUES_PATH), 1);
});

test('findIssue confirms only own bot issues with exact rendered body and bounds pagination', async () => {
  const { privateKeyPem } = makeKeys();
  const payload = samplePayload();
  const body = renderIssue(payload).body;
  const filler = (n) => ({ number: n, user: { login: BOT_LOGIN, type: 'Bot' }, body: 'unrelated' });

  const pages = {
    1: [
      { number: 3, user: { login: 'mallory', type: 'User' }, body },
      { number: 4, user: { login: BOT_LOGIN, type: 'Bot' }, body, pull_request: { url: 'https://api.github.com/x' } },
      { number: 5, user: { login: BOT_LOGIN, type: 'Bot' }, body: `${body}\nextra` },
      { number: 6, user: { login: BOT_LOGIN, type: 'User' }, body },
      { number: 8, user: { login: 'other-app[bot]', type: 'Bot' }, body },
      ...Array.from({ length: 95 }, (_, i) => filler(100 + i)),
    ],
    2: [{ number: 42, user: { login: BOT_LOGIN, type: 'Bot' }, body }],
  };
  const fake = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`GET ${ISSUES_PATH}`]: (req) => json(200, pages[Number(req.url.searchParams.get('page') ?? '1')] ?? []),
  });
  const client = makeClient(fake.fetch, privateKeyPem);

  const found = await client.findIssue(payload);
  assert.equal(found.kind, 'confirmed');
  assert.equal(found.issue, 42);
  assert.equal(fake.count('POST', ISSUES_PATH), 0);

  const onlyImpostors = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`GET ${ISSUES_PATH}`]: (req) =>
      json(200, Number(req.url.searchParams.get('page') ?? '1') === 1 ? pages[1].slice(0, 5) : []),
  });
  const absent = await makeClient(onlyImpostors.fetch, privateKeyPem).findIssue(payload);
  assert.equal(absent.kind, 'absent');

  const endless = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`GET ${ISSUES_PATH}`]: (req) => {
      const page = Number(req.url.searchParams.get('page') ?? '1');
      return json(200, Array.from({ length: 100 }, (_, i) => filler(page * 1000 + i)));
    },
  });
  const bounded = await makeClient(endless.fetch, privateKeyPem).findIssue(payload);
  assert.equal(['absent', 'failed'].includes(bounded.kind), true);
  assert.equal(endless.count('GET', ISSUES_PATH) <= 10, true);
  const perPageOk = endless.calls
    .filter((c) => c.method === 'GET' && c.url.pathname === ISSUES_PATH)
    .every((c) => Number(c.url.searchParams.get('per_page') ?? '30') <= 100);
  assert.equal(perPageOk, true);
});

test('rate limit, server error and timeout never trigger automatic POST retry', async () => {
  const { privateKeyPem } = makeKeys();

  const limited = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`POST ${ISSUES_PATH}`]: () => json(429, { message: 'rate limited' }, { 'retry-after': '30' }),
    [`GET ${ISSUES_PATH}`]: () => json(429, { message: 'rate limited' }, { 'retry-after': '15' }),
  });
  const limitedClient = makeClient(limited.fetch, privateKeyPem);
  const created = await limitedClient.createIssue(samplePayload());
  assert.equal(created.kind, 'rate_limited');
  assert.equal(created.retryAfterMs, 30_000);
  assert.equal(limited.count('POST', ISSUES_PATH), 1);
  const found = await limitedClient.findIssue(samplePayload());
  assert.equal(found.kind, 'rate_limited');
  assert.equal(found.retryAfterMs, 15_000);

  const serverError = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`POST ${ISSUES_PATH}`]: () => json(500, { message: 'boom' }),
  });
  const errResult = await makeClient(serverError.fetch, privateKeyPem).createIssue(samplePayload());
  assert.equal(errResult.kind, 'uncertain');
  assert.equal(serverError.count('POST', ISSUES_PATH), 1);

  const timeout = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken()),
    [`POST ${ISSUES_PATH}`]: () => {
      throw new DOMException('The operation timed out.', 'TimeoutError');
    },
  });
  const timeoutResult = await makeClient(timeout.fetch, privateKeyPem).createIssue(samplePayload());
  assert.equal(timeoutResult.kind, 'uncertain');
  assert.equal(timeout.count('POST', ISSUES_PATH), 1);
});

function makeGatedClient(fetch, privateKeyPem, enabled) {
  return createGitHubClient({
    appId: APP_ID,
    installationId: INSTALLATION_ID,
    privateKeyPem,
    botLogin: BOT_LOGIN,
    fetch,
    now: () => NOW_MS,
    enabled,
  });
}

test('createIssue rechecks enabled after token exchange and skips the issue POST when disabled', async () => {
  const { privateKeyPem } = makeKeys();
  let isEnabled = true;
  const fake = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => {
      isEnabled = false;
      return tokenResponse(syntheticToken(), {
        permissions: { issues: 'write', metadata: 'read' },
        repository_selection: 'selected',
        repositories: [{ name: REPO, full_name: FULL_NAME }],
      });
    },
    [`POST ${ISSUES_PATH}`]: () => json(201, { number: 99 }),
  });
  const client = makeGatedClient(fake.fetch, privateKeyPem, () => isEnabled);

  const result = await client.createIssue(samplePayload());
  assert.equal(result.kind, 'failed');
  assert.equal(fake.count('POST', TOKEN_PATH), 1);
  assert.equal(fake.count('POST', ISSUES_PATH), 0);
});

test('client disabled from the start performs no HTTP requests', async () => {
  const { privateKeyPem } = makeKeys();
  const fake = makeFakeFetch({
    [`POST ${TOKEN_PATH}`]: () => tokenResponse(syntheticToken(), {
      permissions: { issues: 'write', metadata: 'read' },
    }),
    [`POST ${ISSUES_PATH}`]: () => json(201, { number: 99 }),
    [`GET ${ISSUES_PATH}`]: () => json(200, []),
  });
  const client = makeGatedClient(fake.fetch, privateKeyPem, () => false);

  const created = await client.createIssue(samplePayload());
  assert.equal(created.kind, 'failed');
  const found = await client.findIssue(samplePayload());
  assert.equal(found.kind, 'failed');
  assert.equal(fake.calls.length, 0);
});
