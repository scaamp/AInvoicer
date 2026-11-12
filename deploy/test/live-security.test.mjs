import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import { origin, plan, probe, redirectKind, scriptRedirectKind } from '../scripts/live-security.mjs';
import { root } from '../scripts/identity-smoke.mjs';

test('probe origins reject credentials, HTTP remote hosts, path/query/fragment before requests', () => {
    for (const url of ['https://u:p@example.test', 'http://example.test', 'https://example.test/other',
        'https://example.test/?token=hidden', 'https://example.test/#hidden']) assert.throws(() => origin(url));
    assert.equal(origin('https://example.test'), 'https://example.test');
    assert.ok(plan('http://127.0.0.1:8080', 'https://example.test').every(p => !p.authorization || p.authorization === 'Bearer not-a-jwt'));
});

test('actual HTTP probes use only GET, suppress secrets and never follow redirects', async () => {
    let followed = false;
    const methods = [];
    const server = createServer((req, res) => {
        methods.push(req.method);
        if (req.url === '/redirect') { res.writeHead(302, { Location: '/secret', 'Set-Cookie': 'SECRET=never-print' }); res.end(); }
        else if (req.url === '/secret') { followed = true; res.end('SECRET'); }
        else { res.writeHead(401); res.end('safe denial'); }
    });
    await new Promise(r => server.listen(0, '127.0.0.1', r));
    try {
        const base = 'http://127.0.0.1:' + server.address().port;
        assert.equal((await probe({ id: '401', url: base, expected: [401] })).result, 'PASS');
        const report = await probe({ id: 'redirect', url: base + '/redirect', expected: [302] });
        assert.equal(report.result, 'PASS');
        assert.ok(!JSON.stringify(report).includes('SECRET'));
        assert.equal(followed, false);
        assert.deepEqual(methods, ['GET', 'GET']);
    } finally { await new Promise(r => server.close(r)); }
});

test('CAP-07E plan covers legacy projections, service root, RAP children and browser navigation', () => {
    const probes = plan('https://cap.example.test', 'https://router.example.test');
    const ids = probes.map(p => p.id);
    for (const path of ['KsefOperations?$top=1', 'KsefOperations/$count', 'KsefInvoices?$top=1', '']) {
        assert.ok(ids.includes('CAP anonymous ' + path), path);
        assert.ok(ids.includes('CAP malformed JWT ' + path), path);
    }
    for (const path of ['/rap-ui/InvoiceItems?$top=1', '/rap-ui/InvoiceReferences?$top=1']) {
        assert.ok(ids.includes('ROUTER fresh anonymous ' + path), path);
    }
    const navigation = probes.filter(p => p.navigation);
    assert.equal(navigation.length, 2);
    assert.ok(navigation.every(p => p.expectedRedirect.join() === 'xsuaa-authorize,xsuaa-authorize-script' && !p.authorization
        && p.url.startsWith('https://router.example.test/')));
    // Brak żądań do CAP bez kontroli tokenu poza ustalonym originem; same GET (probe nie przyjmuje metody).
    assert.ok(probes.every(p => p.url.startsWith('https://cap.example.test/') || p.url.startsWith('https://router.example.test/')));
});

test('redirect classification accepts only XSUAA authorize and never reports OAuth parameters', async () => {
    const base = 'https://router.example.test/index.html';
    assert.equal(redirectKind('https://sub-1.authentication.eu20.hana.ondemand.com/oauth/authorize?client_id=x', base), 'xsuaa-authorize');
    for (const location of ['https://evil.example/oauth/authorize', 'http://sub.authentication.eu20.hana.ondemand.com/oauth/authorize',
        'https://sub.authentication.eu20.hana.ondemand.com.evil.example/oauth/authorize',
        'https://u:p@sub.authentication.eu20.hana.ondemand.com/oauth/authorize', '/login/callback',
        'https://sub.authentication.eu20.hana.ondemand.com/oauth/token']) {
        assert.equal(redirectKind(location, base), 'other', location);
    }
    assert.equal(redirectKind(null, base), 'none');
    const headers = [];
    for (const [location, expected] of [
        ['https://sub-1.authentication.eu20.hana.ondemand.com/oauth/authorize?client_id=SECRET-CLIENT&state=SECRET-STATE', 'PASS'],
        ['https://evil.example/oauth/authorize?state=SECRET-STATE', 'FAIL']]) {
        const report = await probe({ id: 'nav', url: base, navigation: true, expected: [302], expectedRedirect: ['xsuaa-authorize'] },
            async (url, options) => { headers.push(options.headers); return new Response(null, { status: 302, headers: { Location: location } }); });
        assert.equal(report.result, expected);
        assert.ok(!/SECRET|client_id|state=/.test(JSON.stringify(report)));
    }
    assert.ok(headers.every(h => h['X-Requested-With'] === undefined && h.Accept === 'text/html' && h.Authorization === undefined));
    const xhr = await probe({ id: 'xhr', url: base, expected: [401] }, async (url, options) => {
        assert.equal(options.headers['X-Requested-With'], 'XMLHttpRequest');
        return new Response(null, { status: 401 });
    });
    assert.equal(xhr.redirectKind, undefined);
});

test('AppRouter 200 script redirect passes only as a small no-store page pointing solely to XSUAA', async () => {
    const authorize = 'https://sub-1.authentication.eu20.hana.ondemand.com/oauth/authorize?response_type=code&client_id=SECRET-CLIENT'
        + '&redirect_uri=https%3A%2F%2Frouter.example.test%2Flogin%2Fcallback&state=SECRET-STATE';
    const page = url => `<html><head><script>window.location.replace("${url}" + location.hash);</script></head></html>`;
    const item = { id: 'nav', url: 'https://router.example.test/index.html', navigation: true, expected: [200, 302, 303],
        expectedRedirect: ['xsuaa-authorize', 'xsuaa-authorize-script'] };
    const run = (body, cache = 'no-cache, no-store, must-revalidate') =>
        probe(item, async () => new Response(body, { status: 200, headers: { 'Cache-Control': cache, 'Content-Type': 'text/html' } }));
    const ok = await run(page(authorize));
    assert.equal(ok.result, 'PASS');
    assert.equal(ok.redirectKind, 'xsuaa-authorize-script');
    assert.ok(!/SECRET|client_id|redirect_uri/.test(JSON.stringify(ok)));
    for (const [body, cache] of [
        [page('https://evil.example/oauth/authorize'), undefined],
        [page(authorize) + '<script src="https://cdn.example/x.js"></script>', undefined],
        [page(authorize) + '<script id="sap-ui-bootstrap"></script>', undefined],
        ['{"@odata.context":"$metadata#Invoices","value":[]}', undefined],
        ['<html>KsefService app shell</html>', undefined],
        [page(authorize), 'public, max-age=60'],
        [page(authorize) + 'x'.repeat(5000), undefined]]) {
        const report = await run(body, cache);
        assert.equal(report.result, 'FAIL', body.slice(0, 60));
        assert.ok(!/SECRET/.test(JSON.stringify(report)));
    }
    assert.equal(scriptRedirectKind(302, page(authorize), 'no-store'), 'none');
});

test('HTML200 must actually redirect: reject decoys, local/HTTP targets and injected code', async () => {
    const url = 'https://sub-1.authentication.eu20.hana.ondemand.com/oauth/authorize?state=SECRET';
    const shell = code => `<html><head><script>${code}</script></head></html>`;
    for (const code of [
        `const decoy="${url}"; window.location="/public-app";`,
        `// window.location.replace("${url}");`,
        `window.location.replace("${url}");fetch('/private-data');`,
        `window.location.replace("${url}");</script><body>Public app</body><script>`,
        `/* ${url} */ window.location="http://evil.example";`,
        `window.location.replace("${url}");window.location="//evil.example";`,
        `location="https://sub-1.authentication.eu20.hana.ondemand.com:8443/oauth/authorize"`,
        `location="${url}"+someFunction()`]) {
        const result = await probe({ id: 'decoy', url: 'https://router.example.test/', navigation: true,
            expected: [200], expectedRedirect: ['xsuaa-authorize-script'] }, async () => new Response(shell(code),
            { headers: { 'Cache-Control': 'no-store', 'Content-Type': 'text/html' } }));
        assert.equal(result.result, 'FAIL');
        assert.ok(!JSON.stringify(result).includes('SECRET'));
    }
    const valid = shell(`location="${url}"`);
    assert.equal(scriptRedirectKind(200, valid, 'no-store', 'application/json'), 'none');
    assert.equal(scriptRedirectKind(200, valid, 'x-no-store'), 'none');
});

test('pinned SAP AppRouter cookie page and CSP nonce are recognized without evaluating JavaScript', () => {
    const url = 'https://sub-1.authentication.eu20.hana.ondemand.com/oauth/authorize?state=SYNTHETIC';
    for (const hostIncluded of [false, true]) {
        const path = hostIncluded
            ? "location.href.split('#')[0].substring(location.href.split('#')[0].indexOf(location.host) + location.host.length)"
            : "location.href.split('#')[0].split(location.host)[1]";
        const code = 'document.cookie="fragmentAfterLogin="+encodeURIComponent(location.hash)+";path=/;Secure;SameSite=None;";'
            + `document.cookie="locationAfterLogin="+encodeURIComponent(${path})+";path=/;Secure;SameSite=None;";`
            + 'document.cookie="signature=synthetic-signature;path=/;Secure;SameSite=None;";'
            + `location="${url}"`;
        const page = '<html><head><link rel="shortcut icon" href="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" />'
            + `<script nonce="synthetic123=">${code}</script></head></html>`;
        assert.equal(scriptRedirectKind(200, page, 'no-cache, no-store, must-revalidate'), 'xsuaa-authorize-script');
        assert.equal(scriptRedirectKind(200, page.replace('signature=synthetic-signature', 'signature=x";alert(1);//'), 'no-store'), 'none');
    }
});

test('probe reports exposed CAP index, unsafe error and oversized body as FAIL without raw body', async () => {
    for (const [status, text] of [[200, 'KsefService index'], [500, 'clientsecret=synthetic-hidden'], [401, 'x'.repeat(65537)]]) {
        const report = await probe({ id: 'negative', url: 'https://example.test', expected: [status] },
            async () => new Response(text, { status }));
        assert.equal(report.result, 'FAIL');
        assert.ok(!JSON.stringify(report).includes('synthetic-hidden'));
    }
    const report = await probe({ id: 'offline', url: 'https://example.test', expected: [401] }, () => { throw new Error('synthetic-hidden'); });
    assert.equal(report.result, 'BLOCKED');
    assert.ok(!JSON.stringify(report).includes('synthetic-hidden'));
});

function browser(fetcher, originValue = 'https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com') {
    const context = vm.createContext({ location: { origin: originValue }, crypto: webcrypto, fetch: fetcher,
        AbortSignal, TextDecoder, URL, console });
    vm.runInContext(readFileSync(join(root, 'deploy/scripts/browser-session-acceptance.js'), 'utf8'), context);
    return context.ainvSecurityAcceptance;
}

test('browser helper requires approved origin/mode/reviewed UUID before the first HTTP call', async () => {
    let calls = 0;
    const fetcher = () => { calls++; };
    await assert.rejects(browser(fetcher, 'https://evil.test').run({ mode: 'operator' }), /origin/);
    await assert.rejects(browser(fetcher).run({ mode: 'unknown' }), /mode/);
    await assert.rejects(browser(fetcher).run({ mode: 'operator', rapForeignKey: '../ApplyKsefResult' }), /UUID/);
    assert.equal(calls, 0);
});

test('browser helper uses same-origin GET; nonempty/empty data alone never proves A/B isolation', async () => {
    const calls = [];
    const suite = browser(async (path, options) => {
        calls.push({ path, options });
        assert.ok(path.startsWith('/odata/v4/KsefService/') || path.startsWith('/rap-ui/'));
        assert.equal(options.method, 'GET');
        assert.equal(options.credentials, 'same-origin');
        assert.equal(options.redirect, 'manual');
        assert.equal(options.headers.Authorization, undefined);
        if (path.includes('KsefInbound') || path.includes('SapResultWrites')) return new Response('{}', { status: 404 });
        if (path.endsWith('$metadata')) return new Response('<xml/>');
        return Response.json({ value: [], '@odata.count': 0 });
    });
    const report = await suite.run({ mode: 'operator' });
    assert.equal(report.checks.find(c => c.id === 'CAP InvoiceIntegrationStatus count/list').result, 'BLOCKED_EMPTY_FIXTURE');
    assert.match(report.principal, /BLOCKED/);
    assert.ok(report.checks.some(c => c.result === 'BLOCKED'));
    assert.ok(!JSON.stringify(report).includes('<xml/>'));
    assert.ok(calls.length > 5);
});

test('logout helper accepts denial, rejects still-active session and blocks network errors', async () => {
    const closed = await browser(async () => new Response(null, { status: 401 })).deniedAfterSessionEnd();
    assert.ok(closed.checks.every(c => c.result === 'PASS'));
    const active = await browser(async () => Response.json({ value: [] })).deniedAfterSessionEnd();
    assert.ok(active.checks.every(c => c.result === 'FAIL'));
    const offline = await browser(() => { throw new Error('network'); }).deniedAfterSessionEnd();
    assert.ok(offline.checks.every(c => c.result === 'BLOCKED'));
});

test('no CAP role denies data while authenticated metadata may still be visible', async () => {
    const suite = browser(async path => {
        if (path.endsWith('$metadata')) return new Response('<xml/>');
        if (path.startsWith('/odata/')) return new Response('{}', { status: 403 });
        return Response.json({ value: [], '@odata.count': 0 });
    });
    const report = await suite.run({ mode: 'no-cap-role' });
    assert.equal(report.checks.find(c => c.id === 'CAP metadata').result, 'OBSERVED_ONLY');
    assert.equal(report.checks.find(c => c.id === 'CAP InvoiceIntegrationStatus count/list').result, 'PASS');
    assert.ok(report.checks.every(c => c.result !== 'FAIL'));
});
