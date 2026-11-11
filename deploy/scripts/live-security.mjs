// CAP-07D: tylko GET bez poświadczeń. Nie podążamy za redirectem do IdP ani nie zapisujemy body/cookies.
import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function origin(value) {
    const url = new URL(value);
    if (url.username || url.password || url.pathname !== '/' || url.search || url.hash ||
        !(url.protocol === 'https:' || url.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(url.hostname))) {
        throw new Error('origin must be HTTPS without path, credentials, query or fragment');
    }
    return url.origin;
}

export function plan(cap, router) {
    cap = origin(cap); router = origin(router);
    const service = '/odata/v4/KsefService/';
    const probes = [];
    for (const path of ['$metadata', 'InvoiceIntegrationStatus?$top=1', 'InvoiceIntegrationStatus/$count',
        'InvoiceIntegrationStatus(00000000-0000-0000-0000-000000000000)', 'InboundInvoiceOverview?$top=1']) {
        probes.push({ id: 'CAP anonymous ' + path, url: cap + service + path, expected: [401] });
        probes.push({ id: 'CAP malformed JWT ' + path, url: cap + service + path,
            authorization: 'Bearer not-a-jwt', expected: [401] });
    }
    for (const path of ['/', '/h2-console/', '/actuator/env', '/actuator/health', '/actuator/beans', '/v3/api-docs',
        '/$fiori-preview/', service + 'KsefInboundDocuments', service + 'KsefInboundAccessGrants',
        service + 'SapResultWrites', '/odata/v4/customer.cap_java/KsefInboundDocuments']) {
        probes.push({ id: 'CAP private surface ' + path, url: cap + path, expected: [401, 403, 404] });
    }
    for (const path of ['/index.html', service + '$metadata', service + 'InboundInvoiceOverview?$top=1',
        '/rap-ui/$metadata', '/rap-ui/Invoices?$top=1']) {
        probes.push({ id: 'ROUTER fresh anonymous ' + path, url: router + path, expected: [302, 303, 401] });
    }
    return probes;
}

/** Ograniczony odczyt, redakcja przed zbudowaniem raportu; brak logowania błędów fetch i nagłówków. */
export async function probe(item, fetcher = fetch) {
    const correlationId = randomUUID();
    const at = new Date().toISOString();
    try {
        const response = await fetcher(item.url, { method: 'GET', redirect: 'manual', credentials: 'omit',
            signal: AbortSignal.timeout(15000), headers: { Accept: 'application/json',
                'X-Requested-With': 'XMLHttpRequest', 'X-CorrelationID': correlationId,
                ...(item.authorization ? { Authorization: item.authorization } : {}) } });
        const reader = response.body?.getReader();
        let body = '', size = 0;
        if (reader) {
            try {
                while (true) {
                    const { value, done } = await reader.read();
                    if (done) break;
                    size += value.length;
                    if (size > 65536) break;
                    body += Buffer.from(value).toString('utf8');
                }
            } finally { await reader.cancel(); }
        }
        const contractExposed = /KsefService|InvoiceIntegrationStatus|InboundInvoiceOverview/.test(body)
            && response.status === 200;
        const sensitiveError = response.status >= 400 && /stackTrace|java\.lang\.|clientsecret|VCAP_SERVICES|jdbc:/i.test(body);
        const id = response.headers.get('x-vcap-request-id');
        return { id: item.id, at, correlationId,
            requestId: /^[a-f\d-]{16,80}$/i.test(id || '') ? id : undefined,
            status: response.status, contractExposed, sensitiveError, bodyLimitExceeded: size > 65536,
            result: item.expected.includes(response.status) && !sensitiveError && !contractExposed && size <= 65536 ? 'PASS' : 'FAIL' };
    } catch {
        return { id: item.id, at, correlationId, result: 'BLOCKED', reason: 'HTTP read unavailable; raw error suppressed' };
    }
}

async function main() {
    const [cap, router, file = 'deploy/build/live-security.json'] = process.argv.slice(2);
    const probes = plan(cap, router); // Walidacja obu originów przed pierwszym żądaniem.
    const checks = [];
    for (const item of probes) checks.push(await probe(item));
    const report = { kind: 'LIVE_UNAUTHENTICATED_READ_ONLY', at: new Date().toISOString(), checks,
        authenticatedJwtAndRoles: 'BLOCKED: requires existing business-user browser session',
        h01Principal: 'BLOCKED: HTTP status and request ID alone do not identify H01 principal' };
    const out = resolve(file);
    mkdirSync(dirname(out), { recursive: true }); writeFileSync(out, JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report, null, 2));
    if (checks.some(c => c.result !== 'PASS')) process.exitCode = 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try { await main(); }
    catch { console.error('Expected: node live-security.mjs <CAP HTTPS origin> <AppRouter HTTPS origin> [report.json]'); process.exitCode = 2; }
}
