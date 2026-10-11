// RED-phase tests for src/protocol.mjs: strict contract, fixed rendering, bounded streaming reads.
// Enum values mirror app SanitizedAppFault (Fault/Component), the authoritative contract.
// The PERSONAL value is a synthetic placeholder used only as test input.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validatePayload, signature, renderIssue, readPayload } from '../src/protocol.mjs';

const FIELDS = ['android_api', 'app_version_code', 'component', 'fault', 'schema'];
const FAULTS = ['NULL_POINTER', 'ILLEGAL_STATE', 'INDEX_BOUNDS', 'CONCURRENT_MODIFICATION', 'ASSERTION'];
const COMPONENTS = ['PLAYER', 'UI', 'APP'];
const PERSONAL = '[redacted-email]';
const URL = 'https://relay.invalid/v1/fault';
const JSON_HEADERS = { 'content-type': 'application/json' };
const encoder = new TextEncoder();

const valid = () => ({ schema: 1, fault: 'NULL_POINTER', component: 'PLAYER', app_version_code: 1203, android_api: 34 });

// Request boundary stand-in exposing only headers and a pull-counted body stream.
function streamRequest(chunks, headers, pulls = { count: 0 }) {
  let index = 0;
  const body = new ReadableStream({
    pull(controller) {
      pulls.count += 1;
      const next = typeof chunks === 'function' ? chunks(index) : chunks[index];
      index += 1;
      if (next === undefined) controller.close();
      else controller.enqueue(encoder.encode(next));
    },
  });
  return { method: 'POST', url: URL, headers: new Headers(headers), body };
}

const safeError = (error) => {
  assert.ok(error instanceof Error);
  assert.ok(!error.message.includes(PERSONAL));
  return true;
};

test('validatePayload accepts the exact contract and returns a fresh five-field object', () => {
  const input = valid();
  const result = validatePayload(input);
  assert.notEqual(result, input);
  assert.deepEqual(Object.keys(result).sort(), FIELDS);
  assert.deepEqual(result, valid());
  assert.deepEqual(validatePayload({ ...valid(), android_api: 23 }).android_api, 23);
  assert.deepEqual(validatePayload({ ...valid(), android_api: 100 }).android_api, 100);
});

test('validatePayload accepts every app sanitizer Fault and Component value', () => {
  for (const fault of FAULTS) {
    for (const component of COMPONENTS) {
      const input = { ...valid(), fault, component };
      assert.deepEqual(validatePayload(input), input, `${fault}/${component}`);
    }
  }
});

test('validatePayload rejects extra, missing, mistyped, out-of-range and unknown values', () => {
  const omit = (key) => {
    const p = valid();
    delete p[key];
    return p;
  };
  const cases = [
    null,
    [],
    'NULL_POINTER',
    { ...valid(), device: 'pixel' },
    { ...valid(), message: PERSONAL },
    { ...valid(), url: 'https://example.com' },
    JSON.parse('{"schema":1,"fault":"NULL_POINTER","component":"PLAYER","app_version_code":1203,"android_api":34,"__proto__":{}}'),
    ...FIELDS.map(omit),
    { ...valid(), schema: 2 },
    { ...valid(), schema: '1' },
    { ...valid(), fault: PERSONAL },
    { ...valid(), fault: 'network_error' },
    { ...valid(), component: 'video_provider' },
    { ...valid(), component: 1 },
    { ...valid(), app_version_code: 0 },
    { ...valid(), app_version_code: -1 },
    { ...valid(), app_version_code: 1.5 },
    { ...valid(), app_version_code: '1203' },
    { ...valid(), app_version_code: Number.POSITIVE_INFINITY },
    { ...valid(), android_api: 22 },
    { ...valid(), android_api: 101 },
    { ...valid(), android_api: Number.NaN },
    { ...valid(), android_api: true },
  ];
  for (const input of cases) assert.equal(validatePayload(input), null, JSON.stringify(input));
});

test('validatePayload rejects strings outside the app sanitizer enums, including legacy and case variants', () => {
  const badFaults = ['crash', 'anr', 'null_pointer', 'Null_Pointer', ' NULL_POINTER', 'NULL_POINTER ', 'NullPointerException', 'java.lang.NullPointerException', ''];
  const badComponents = ['playback', 'offline', 'player', 'Player', 'ui', 'app', ' APP', 'PLAYER ', 'org.schabi.newpipe.player.', ''];
  for (const fault of badFaults) assert.equal(validatePayload({ ...valid(), fault }), null, `fault ${JSON.stringify(fault)}`);
  for (const component of badComponents) {
    assert.equal(validatePayload({ ...valid(), component }), null, `component ${JSON.stringify(component)}`);
  }
  assert.equal(validatePayload({ ...valid(), fault: 'PLAYER' }), null);
  assert.equal(validatePayload({ ...valid(), component: 'NULL_POINTER' }), null);
});

test('signature is deterministic, order-independent, ASCII-only and distinguishes every field', () => {
  const base = signature(valid());
  const reordered = signature({ android_api: 34, app_version_code: 1203, component: 'PLAYER', fault: 'NULL_POINTER', schema: 1 });
  assert.equal(base, reordered);
  assert.match(base, /^[\x21-\x7e]+$/);
  assert.notEqual(signature({ ...valid(), android_api: 35 }), base);
  assert.notEqual(signature({ ...valid(), app_version_code: 1204 }), base);
  assert.notEqual(signature({ ...valid(), fault: 'ILLEGAL_STATE' }), base);
  assert.notEqual(signature({ ...valid(), component: 'APP' }), base);
});

test('renderIssue uses a fixed title and a body of only enums, numbers and the server marker', () => {
  const a = renderIssue(valid());
  const b = renderIssue({ ...valid(), fault: 'ILLEGAL_STATE', component: 'APP', android_api: 23 });
  assert.equal(a.title, b.title);
  assert.deepEqual(Object.keys(a).sort(), ['body', 'title']);
  for (const text of [a.title, a.body]) {
    assert.match(text, /^[\x20-\x7e\n]*$/);
    assert.ok(!text.includes('@'));
    assert.ok(!/https?:/i.test(text));
  }
  assert.ok(a.body.includes(signature(valid())));
  for (const value of ['NULL_POINTER', 'PLAYER', '1203', '34']) assert.ok(a.body.includes(value));
  assert.ok(a.body.length < 2000);

  assert.throws(() => renderIssue({ ...valid(), note: PERSONAL }), safeError);
  assert.throws(() => renderIssue({ ...valid(), fault: PERSONAL }), safeError);
  assert.throws(() => renderIssue({ ...valid(), fault: 'crash' }), safeError);
  assert.throws(() => renderIssue({ ...valid(), component: 'playback' }), safeError);
});

test('readPayload accepts JSON bodies, including chunked streams and exactly 512 bytes', async () => {
  const json = JSON.stringify(valid());
  const real = new Request(URL, { method: 'POST', headers: JSON_HEADERS, body: json });
  assert.deepEqual(await readPayload(real), valid());

  const chunked = streamRequest([json.slice(0, 10), json.slice(10)], { 'content-type': 'application/json; charset=utf-8' });
  assert.deepEqual(await readPayload(chunked), valid());

  const padded = json + ' '.repeat(512 - encoder.encode(json).length);
  assert.equal(encoder.encode(padded).length, 512);
  assert.deepEqual(await readPayload(streamRequest([padded], JSON_HEADERS)), valid());
  await assert.rejects(readPayload(streamRequest([padded + ' '], JSON_HEADERS)), safeError);
});

test('readPayload rejects non-JSON, malformed and extra-field bodies without echoing input', async () => {
  const json = JSON.stringify(valid());
  await assert.rejects(readPayload(streamRequest([json], { 'content-type': 'text/plain' })), safeError);
  await assert.rejects(readPayload(streamRequest([json], {})), safeError);
  await assert.rejects(readPayload(streamRequest([`{"fault":"${PERSONAL}`], JSON_HEADERS)), safeError);
  await assert.rejects(
    readPayload(streamRequest([JSON.stringify({ ...valid(), email: PERSONAL })], JSON_HEADERS)),
    safeError,
  );
  await assert.rejects(
    readPayload(streamRequest([JSON.stringify({ ...valid(), fault: PERSONAL })], JSON_HEADERS)),
    safeError,
  );
  await assert.rejects(
    readPayload(streamRequest([JSON.stringify({ ...valid(), fault: 'crash', component: 'playback' })], JSON_HEADERS)),
    safeError,
  );
});

test('readPayload enforces 512 bytes while streaming despite a false Content-Length', async () => {
  const pulls = { count: 0 };
  const endless = streamRequest(() => ' '.repeat(100), { ...JSON_HEADERS, 'content-length': '10' }, pulls);
  await assert.rejects(readPayload(endless), safeError);
  assert.ok(pulls.count <= 10, `read ${pulls.count} chunks`);

  const declared = streamRequest([JSON.stringify(valid())], { ...JSON_HEADERS, 'content-length': '513' });
  await assert.rejects(readPayload(declared), safeError);
});
