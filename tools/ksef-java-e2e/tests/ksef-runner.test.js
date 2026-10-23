import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import {
  readConfig, loadEnv, callJavaClient, poll, invoiceResult, requireJwt,
  authenticate, openSession, sendInvoice, checkInvoiceStatus, closeSession,
  downloadUpo, reserveSend, preflight, prepareInvoice, RunnerError, verifyRuntime, checkSessionStatus,
} from '../ksef-runner.js';

const quiet = () => {};
const config = { ...readConfig({}), intervalMs: 1, attempts: 3, pollTimeoutMs: 100, httpTimeoutMs: 30 };
const number = '1234563218-20261023-ABCDEF123456-AB';
const jwt = () => `e30.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url')}.signature`;

// Testujemy przede wszystkim brak retry mutacji, interpretację biznesową,
// trwałą blokadę i rzeczywiste limity transportu, zamiast kryptografii SDK Java.
test('konfiguracja blokuje DEMO/PROD, obcy NIP i nielokalny URL', () => {
  for (const env of [{ KSEF_ENV: 'PROD' }, { KSEF_ENV: 'DEMO' }, { KSEF_NIP: '1111111111' },
    { JAVA_CLIENT_URL: 'https://api-test.ksef.mf.gov.pl' }, { JAVA_CLIENT_URL: 'http://localhost:8082/path' },
    { JAVA_CLIENT_URL: 'http://secret@localhost:8082' }, { MAX_POLL_ATTEMPTS: '0' }, { POLL_INTERVAL_MS: 'NaN' }])
    assert.throws(() => readConfig(env), { code: 'CONFIG' });
  assert.equal(readConfig({}).nip, '1234563218');
});

test('.env zachowuje konfigurację procesu i nie wymaga dotenv', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'ksef-env-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, '.env');
  await writeFile(path, '# przykład\nKSEF_ENV=TEST\nJAVA_CLIENT_URL="http://localhost:8082"\n');
  const env = { KSEF_ENV: 'PROD' };
  await loadEnv(path, env);
  assert.equal(env.KSEF_ENV, 'PROD');
  assert.equal(env.JAVA_CLIENT_URL, 'http://localhost:8082');
});

test('HTTP: surowy JWT dla Java, JSON, brak sekretów w logu', async () => {
  const logs = [];
  const result = await callJavaClient(config, 'POST', '/open-session', { sample: 1 }, {
    token: 'jwt-secret', log: line => logs.push(line), fetchImpl: async (url, init) => {
      assert.equal(url.origin, 'http://localhost:8082');
      assert.equal(init.headers.Authorization, 'jwt-secret');
      assert.equal(init.redirect, 'error');
      assert.equal(init.body, '{"sample":1}');
      return Response.json({ referenceNumber: 'session' });
    },
  });
  assert.equal(result.data.referenceNumber, 'session');
  assert.ok(!logs.join().includes('jwt-secret'));
});

test('pusty close, binarne UPO i niepoprawny JSON', async () => {
  const call = response => callJavaClient(config, 'GET', '/test', undefined, { log: quiet, fetchImpl: async () => response });
  assert.equal((await call(new Response(null, { status: 204 }))).data, null);
  await assert.rejects(call(new Response('<html>error</html>')), { code: 'CONTRACT' });
  const result = await callJavaClient(config, 'GET', '/upo', undefined, {
    binary: true, log: quiet, fetchImpl: async () => new Response('<Potwierdzenie/>'),
  });
  assert.ok(Buffer.isBuffer(result.data));
});

test('HTTP 500 i błąd sieci send nie powodują automatycznego retry ani wycieku sekretu', async () => {
  for (const kind of ['http', 'network']) {
    let count = 0;
    const logs = [];
    const error = await callJavaClient(config, 'POST', '/send-invoice/session/1234563218', undefined, {
      token: 'jwt-secret', log: line => logs.push(line), fetchImpl: async () => {
        count++;
        if (kind === 'network') throw new Error('jwt-secret');
        return Response.json({ accessToken: 'jwt-secret' }, { status: 500 });
      },
    }).catch(error => error);
    assert.equal(count, 1);
    assert.ok(!`${error.message}${logs.join()}`.includes('jwt-secret'));
    assert.equal(error.code, kind === 'http' ? 'HTTP' : 'NETWORK');
  }
});

test('AbortController ogranicza także oczekiwanie na HTTP', async () => {
  await assert.rejects(callJavaClient(config, 'POST', '/authenticationProcess/1234563218', undefined, {
    timeoutMs: 5, log: quiet,
    fetchImpl: (_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new Error('abort')), { once: true });
    }),
  }), { code: 'TIMEOUT' });
});

test('timeout obejmuje również odczyt body po odebraniu nagłówków', async () => {
  await assert.rejects(callJavaClient(config, 'GET', '/invoice-status', undefined, {
    timeoutMs: 5, log: quiet, fetchImpl: async (_url, init) => new Response(new ReadableStream({
      start(controller) {
        init.signal.addEventListener('abort', () => controller.error(new Error('body aborted')), { once: true });
      },
    })),
  }), { code: 'TIMEOUT' });
});

test('HTTP nie pozwala wysłać JWT poza lokalną Javę', async () => {
  let calls = 0;
  for (const path of ['https://example.com/steal', '//example.com/steal'])
    await assert.rejects(callJavaClient(config, 'GET', path, undefined, {
      fetchImpl: async () => { calls++; },
    }), { code: 'CONFIG' });
  assert.equal(calls, 0);
});

test('polling: 100 -> 150 -> 200 oraz końcowy numer KSeF', async () => {
  const codes = [100, 150, 200];
  const result = await checkInvoiceStatus(async (_method, path) => {
    assert.equal(new URL(path, config.baseUrl).searchParams.get('referenceNumber'), 'session');
    return { data: { status: { code: codes.shift() }, ksefNumber: number } };
  }, config, 'session', 'invoice', quiet);
  assert.deepEqual(result, { businessStatus: 200, ksefNumber: number });
  assert.equal(codes.length, 0);
});

test('odrzucenie, duplikat i brak numeru KSeF nie są sukcesem', () => {
  for (const [code, expected] of [[440, 'DUPLICATE'], [450, 'REJECTED'], [500, 'REJECTED'], [999, 'REJECTED']])
    assert.throws(() => invoiceResult({ status: { code } }), { code: expected });
  assert.throws(() => invoiceResult({ status: { code: 200 } }), { code: 'CONTRACT' });
  assert.throws(() => invoiceResult({ status: { code: '200' }, ksefNumber: number }), { code: 'CONTRACT' });
});

test('polling kończy się limitem prób i nie ponawia 401', async () => {
  let count = 0;
  await assert.rejects(poll(config, async () => { count++; return null; }, { log: quiet }), { code: 'PENDING' });
  assert.equal(count, 3);
  count = 0;
  await assert.rejects(poll(config, async () => { count++; throw new RunnerError('HTTP 401', 'HTTP', 401); }, { log: quiet }), { httpStatus: 401 });
  assert.equal(count, 1);
});

test('auth: JWT po redeem, brak retry i HTTP 202 bez sukcesu', async () => {
  const token = jwt();
  assert.equal(requireJwt({ accessToken: { token } }), token);
  assert.throws(() => requireJwt({ accessToken: { token: 'opaque' } }), { code: 'AUTH' });
  assert.throws(() => requireJwt({ accessToken: { token: 'e30.eyJleHAiOjF9.sig' } }), { code: 'AUTH' });
  let calls = 0;
  await assert.rejects(authenticate(async () => { calls++; return { status: 202, data: { accessToken: { token } } }; }, config.nip), { code: 'AUTH' });
  assert.equal(calls, 1);
  await assert.rejects(authenticate(async () => ({ status: 200,
    data: { status: { code: 100 }, accessToken: { token } } }), config.nip), { code: 'AUTH' });
  await assert.rejects(authenticate(async () => ({ status: 200, data: { accessToken: { token } } }), config.nip), { code: 'AUTH' });
  assert.equal(await authenticate(async () => ({ status: 200, data: { status: { code: 200 }, accessToken: { token } } }), config.nip), token);
});

test('trwała blokada wysyłki przetrwa awarię i równoległe próby', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'ksef-guard-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const results = await Promise.allSettled([reserveSend(dir, { session: 'a' }), reserveSend(dir, { session: 'b' })]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.code, 'SEND_GUARD');
  assert.ok(JSON.parse(await readFile(join(dir, 'send-attempt.json'), 'utf8')).session);
  await assert.rejects(reserveSend(dir, {}), { code: 'SEND_GUARD' });
});

test('UPO: odczekanie na 404, binarny XML, zapis i brak tokenu w pliku', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'ksef-upo-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  let calls = 0;
  const xml = '<Potwierdzenie xmlns="http://upo.schematy.mf.gov.pl/KSeF/v4"><Nr>TEST</Nr></Potwierdzenie>';
  const result = await downloadUpo(async (_method, path, _body, options) => {
    assert.ok(path.startsWith('/invoice-upo-by-invoice-number?'));
    assert.equal(options.binary, true);
    if (++calls === 1) throw new RunnerError('not found', 'HTTP', 404);
    return { status: 200, data: Buffer.from(xml) };
  }, { ...config, outputDir: dir }, 'session', 'invoice', quiet);
  assert.equal(result.status, 'DOWNLOADED');
  assert.equal(await readFile(result.path, 'utf8'), xml);
});

test('UPO: brak dokumentu to PENDING, JSON/HTML nie są UPO', async () => {
  const result = await downloadUpo(async () => ({ status: 204, data: Buffer.alloc(0) }), config, 's', 'i', quiet);
  assert.equal(result.status, 'PENDING');
  for (const text of ['{"status":200}', '<html>error</html>'])
    await assert.rejects(downloadUpo(async () => ({ status: 200, data: Buffer.from(text) }), config, 's', 'i', quiet), { code: 'CONTRACT' });
});

test('preflight wykonuje tylko OPTIONS, sprawdza Allow i nie uruchamia send', async () => {
  let calls = 0;
  await preflight(config, async (_url, init) => {
    assert.equal(init.method, 'OPTIONS'); calls++;
    return new Response(null, { status: 200, headers: { Allow: 'GET, POST, OPTIONS' } });
  }, quiet);
  assert.equal(calls, 7);
  await assert.rejects(preflight(config, async () => new Response(null, { status: 404 }), quiet), { code: 'CONTRACT' });
});

test('symulacja: auth -> open -> send -> status -> close -> UPO bez kryptografii Node', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'ksef-flow-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const requests = [];
  const token = jwt();
  const call = async (method, path) => {
    requests.push(`${method} ${path.split('?')[0]}`);
    if (path.startsWith('/authenticationProcess/')) return { status: 200, data: { status: { code: 200 }, accessToken: { token } } };
    if (path === '/open-session') return { status: 200, data: { referenceNumber: 'session' } };
    if (path.startsWith('/send-invoice/')) return { status: 200, data: { referenceNumber: 'invoice' } };
    if (path.startsWith('/invoice-status?')) return { status: 200, data: { status: { code: 200 }, ksefNumber: number } };
    if (path.startsWith('/close-session/')) return { status: 200, data: null };
    return { status: 200, data: Buffer.from('<Potwierdzenie xmlns="http://upo.schematy.mf.gov.pl/KSeF/v4"></Potwierdzenie>') };
  };
  assert.equal(await authenticate(call, config.nip), token);
  assert.equal((await prepareInvoice()).format, 'FA(3)');
  const session = await openSession(call);
  const invoice = await sendInvoice(call, session, config.nip);
  assert.equal((await checkInvoiceStatus(call, config, session, invoice, quiet)).ksefNumber, number);
  assert.equal((await closeSession(call, session)).status, 'CLOSE_ACCEPTED');
  assert.equal((await downloadUpo(call, { ...config, outputDir: dir }, session, invoice, quiet)).status, 'DOWNLOADED');
  assert.deepEqual(requests, ['POST /authenticationProcess/1234563218', 'POST /open-session',
    'POST /send-invoice/session/1234563218', 'GET /invoice-status', 'POST /close-session/session',
    'GET /invoice-upo-by-invoice-number']);
});

// Poniżej uruchamiamy prawdziwy CLI w izolowanym katalogu i testowy serwer HTTP.
// Serwer istnieje wyłącznie w testach i nie łączy się z KSeF ani z portem 8082.
async function cliFixture(t, { invoiceCode = 450, closeStatus = 200 } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'ksef-cli-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const source = join(dir, 'java');
  const api = join(source, 'demo-web-app/src/main/java/pl/akmf/ksef/sdk/api');
  const resources = join(source, 'demo-web-app/src/main/resources');
  await mkdir(api, { recursive: true });
  await mkdir(join(resources, 'xml/invoices/sample'), { recursive: true });
  await mkdir(join(dir, 'fixtures'));
  await copyFile(new URL('../ksef-runner.js', import.meta.url), join(dir, 'ksef-runner.mjs'));
  const xml = await readFile(new URL('../fixtures/test-invoice.xml', import.meta.url), 'utf8');
  await writeFile(join(dir, 'fixtures/test-invoice.xml'), xml);
  await writeFile(join(resources, 'xml/invoices/sample/invoice-template_v3.xml'),
    xml.replace('<NIP>1234563218</NIP>', '<NIP>#nip#</NIP>').replaceAll('2025-06-15', '#invoicing_date#').replaceAll('E2E-PREVIEW', '#invoice_number#'));
  await writeFile(join(resources, 'application.yaml'), 'base-uri: "https://api-test.ksef.mf.gov.pl"\nsuffix-uri: "v2"');
  await writeFile(join(api, 'AuthController.java'), '/authenticationProcess/{context}\ngetStatus().getCode() != 200\nredeemToken(');
  await writeFile(join(api, 'OnlineSessionController.java'), '/send-invoice/{referenceNumber}/{contextIdentifier}\ndemo-web-app/src/main/resources/xml/invoices/sample/invoice-template_v3.xml\nLocalDate.of(2025, 6, 15)');
  const state = { invoiceCode, closeStatus, auth: 0, open: 0, send: 0, close: 0 };
  const server = createServer((req, res) => {
    if (req.method === 'OPTIONS') { res.writeHead(200, { Allow: 'GET, POST, OPTIONS' }); res.end(); return; }
    const path = req.url.split('?')[0];
    let body;
    if (path === '/e2e/config') body = { environment: 'TEST', baseUri: 'https://api-test.ksef.mf.gov.pl', suffixUri: 'v2' };
    else if (path.startsWith('/authenticationProcess/')) { state.auth++; body = { status: { code: 200 }, accessToken: { token: jwt() } }; }
    else if (path === '/open-session') { state.open++; body = { referenceNumber: 'session' }; }
    else if (path.startsWith('/send-invoice/')) { state.send++; body = { referenceNumber: 'invoice' }; }
    else if (path === '/invoice-status') body = { status: { code: state.invoiceCode }, ksefNumber: number };
    else if (path === '/session-status') body = { status: { code: 200 }, upo: { pages: [{ referenceNumber: 'upo-page' }] } };
    else if (path.startsWith('/close-session/')) { state.close++; res.writeHead(state.closeStatus); res.end(); return; }
    else if (path === '/invoice-upo-by-invoice-number') {
      res.end('<Potwierdzenie xmlns="http://upo.schematy.mf.gov.pl/KSeF/v4"></Potwierdzenie>'); return;
    } else { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  const run = async flag => {
    try {
      const result = await promisify(execFile)(process.execPath, [join(dir, 'ksef-runner.mjs'), flag], {
        timeout: 10000, env: { ...process.env, JAVA_CLIENT_URL: `http://127.0.0.1:${server.address().port}`,
          KSEF_ENV: 'TEST', KSEF_NIP: config.nip, JAVA_CLIENT_SOURCE_DIR: source,
          POLL_INTERVAL_MS: '1', MAX_POLL_ATTEMPTS: '2', POLL_TIMEOUT_MS: '1000', HTTP_TIMEOUT_MS: '500' },
      });
      return { ...result, code: 0 };
    } catch (error) { if (typeof error.code !== 'number') throw error; return error; }
  };
  return { state, run, report: async () => JSON.parse(await readFile(join(dir, 'output/result.json'), 'utf8')) };
}

test('CLI: odrzucenie domyka sesję, blokuje drugi send i pozwala wznowić same odczyty', async t => {
  const fixture = await cliFixture(t);
  assert.equal((await fixture.run('--send')).code, 1);
  const rejected = await fixture.report();
  assert.equal(rejected.error.code, 'REJECTED');
  assert.equal(rejected.step, 'CHECK INVOICE STATUS');
  assert.equal(rejected.close.status, 'CLOSE_ACCEPTED');
  assert.equal(fixture.state.close, 1);
  assert.equal((await fixture.run('--send')).code, 1);
  assert.deepEqual(await fixture.report(), rejected); // Ponowny send nie niszczy oryginalnego raportu.
  assert.equal(fixture.state.auth, 1);
  fixture.state.invoiceCode = 200;
  assert.equal((await fixture.run('--resume')).code, 0);
  const resumed = await fixture.report();
  assert.equal(resumed.upo.status, 'DOWNLOADED');
  assert.equal(resumed.sessionStatus, 'PROCESSED');
  assert.deepEqual([fixture.state.open, fixture.state.send, fixture.state.close], [1, 1, 1]);
});

test('runtime TEST jest weryfikowany przed autoryzacją, niezależnie od .env', async () => {
  for (const data of [
    { environment: 'PROD', baseUri: 'https://api.ksef.mf.gov.pl', suffixUri: 'v2' },
    { environment: 'TEST', baseUri: 'https://api-demo.ksef.mf.gov.pl', suffixUri: 'v2' },
    { environment: 'TEST', baseUri: 'https://api-test.ksef.mf.gov.pl', suffixUri: 'v1' },
  ]) await assert.rejects(verifyRuntime(config, async () => Response.json(data), quiet), { code: 'CONFIG' });
  await verifyRuntime(config, async () => Response.json({ environment: 'TEST', baseUri: 'https://api-test.ksef.mf.gov.pl', suffixUri: 'v2' }), quiet);
});

test('sesja: 170 -> 200 bez UPO -> 200 z UPO; podpisane URL nie trafiają do wyniku', async () => {
  const states = [
    { status: { code: 170 } },
    { status: { code: 200 } },
    { status: { code: 200 }, upo: { pages: [{ downloadUrl: 'signed-url-secret' }] } },
  ];
  const result = await checkSessionStatus(async () => ({ data: states.shift() }), config, 'session', quiet);
  assert.equal(result.status, 'PROCESSED');
  assert.equal(result.businessStatus, 200);
  assert.equal(result.upoPages, 1);
  assert.ok(!JSON.stringify(result).includes('signed-url-secret'));
});

test('sesja: limit czasu UPO to PENDING, odrzucenie to błąd końcowy', async () => {
  const result = await checkSessionStatus(async () => ({ data: { status: { code: 200 } } }), config, 'session', quiet);
  assert.equal(result.status, 'PENDING');
  assert.equal(result.businessStatus, 200);
  await assert.rejects(checkSessionStatus(async () => ({ data: { status: { code: 445 } } }), config, 'session', quiet), { code: 'SESSION_REJECTED' });
});

test('CLI: niejednoznaczny close wskazuje właściwy etap i nie jest ponawiany', async t => {
  const fixture = await cliFixture(t, { invoiceCode: 200, closeStatus: 500 });
  assert.equal((await fixture.run('--send')).code, 1);
  const result = await fixture.report();
  assert.equal(result.step, 'CLOSE SESSION');
  assert.equal(result.error.httpStatus, 500);
  assert.equal(result.close.status, 'OUTCOME_UNKNOWN');
  await fixture.run('--resume');
  assert.equal(fixture.state.close, 1);
  assert.equal(fixture.state.send, 1);
});
