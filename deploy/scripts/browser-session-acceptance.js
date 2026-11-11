// Wklej całą zawartość do DevTools w już zalogowanym AppRouterze. Brak eksportu tokenów/cookies/body.
// Ten plik nie jest wdrażany. Każde żądanie jest GET do tego samego originu; bez automatycznego logout.
(() => {
    'use strict';
    const expectedOrigin = 'https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com';
    const cap = '/odata/v4/KsefService/';
    const uuid = value => /^[a-f\d]{8}-(?:[a-f\d]{4}-){3}[a-f\d]{12}$/i.test(value || '');
    const safeId = value => /^[a-f\d-]{16,80}$/i.test(value || '') ? value : undefined;

    /** Odczyt syntetycznych fixtures i liczności; identyfikatory służą jedynie porównaniu w pamięci. */
    async function run(options = {}) {
        if (location.origin !== expectedOrigin) throw new Error('Run only on the approved AppRouter origin');
        if (!['operator', 'no-cap-role', 'no-h01-role', 'no-attributes', 'ambiguous-attributes'].includes(options.mode)) {
            throw new Error('Choose an explicit acceptance mode');
        }
        for (const key of ['capOwnKey', 'capForeignKey', 'rapOwnKey', 'rapForeignKey', 'rapItemForeignKey', 'rapReferenceForeignKey']) {
            if (options[key] !== undefined && !uuid(options[key])) throw new Error('Fixture key must be a reviewed UUID');
        }
        const checks = [];
        async function check(id, path, evaluate) {
            const correlationId = crypto.randomUUID();
            const at = new Date().toISOString();
            try {
                const response = await fetch(path, { method: 'GET', credentials: 'same-origin', redirect: 'manual',
                    signal: AbortSignal.timeout(15000), headers: { Accept: path.endsWith('$metadata') ? 'application/xml' : 'application/json',
                        'X-Requested-With': 'XMLHttpRequest', 'X-CorrelationID': correlationId } });
                // Nie przetwarzamy strony loginu jako danych ani PASS logout/session-expiry.
                const length = Number(response.headers.get('content-length') || 0);
                if (length > 262144) { await response.body?.cancel(); throw new Error('response too large'); }
                const reader = response.body?.getReader();
                const decoder = new TextDecoder();
                let body = '', size = 0;
                if (reader) {
                    try {
                        while (true) {
                            const { value, done } = await reader.read();
                            if (done) break;
                            size += value.length;
                            if (size > 262144) throw new Error('response too large');
                            body += decoder.decode(value, { stream: true });
                        }
                    } finally { await reader.cancel(); }
                }
                let data;
                try { data = JSON.parse(body); } catch { /* metadata/count nie są JSON */ }
                const safeError = response.status < 400 || !/clientsecret|VCAP_SERVICES|stackTrace|java\.lang\.|jdbc:/i.test(body);
                const verdict = evaluate(response.status, data, body);
                checks.push({ id, at, correlationId, requestId: safeId(response.headers.get('x-vcap-request-id')),
                    status: response.status, result: safeError ? verdict : 'FAIL', safeError,
                    ...(Array.isArray(data?.value) ? { rows: data.value.length, count: data['@odata.count'] } : {}) });
            } catch { checks.push({ id, at, correlationId, result: 'BLOCKED', reason: 'session, network or bounded read unavailable' }); }
        }
        const operator = options.mode !== 'no-cap-role';
        const empty = ['no-attributes', 'ambiguous-attributes'].includes(options.mode);
        // Service root wymaga authenticated-user; restrykcje KsefOperator dotyczą danych, nie samego schematu.
        await check('CAP metadata', cap + '$metadata', s => !operator
            ? [200, 403].includes(s) ? 'OBSERVED_ONLY' : 'FAIL' : s === 200 ? 'PASS' : 'FAIL');
        for (const entity of ['InvoiceIntegrationStatus', 'InboundInvoiceOverview']) {
            await check('CAP ' + entity + ' count/list', cap + entity + '?$top=1&$count=true', (s, d) => {
                if (!operator) return s === 403 ? 'PASS' : 'FAIL';
                if (s !== 200 || !Array.isArray(d?.value) || !Number.isInteger(d['@odata.count'])) return 'FAIL';
                if (empty) return d['@odata.count'] === 0 && d.value.length === 0 ? 'PASS' : 'FAIL';
                return d['@odata.count'] > 0 ? 'OBSERVED_ONLY' : 'BLOCKED_EMPTY_FIXTURE';
            });
        }
        if (options.capForeignKey) {
            await check('CAP foreign key', cap + 'InvoiceIntegrationStatus(' + options.capForeignKey + ')',
                s => s === (operator ? 404 : 403) ? 'PASS' : 'FAIL');
            await check('CAP foreign filter/count', cap + 'InvoiceIntegrationStatus/$count?$filter=' +
                encodeURIComponent('operationId eq ' + options.capForeignKey),
                (s, d, b) => !operator ? s === 403 ? 'PASS' : 'FAIL' : s === 200 && b.trim() === '0' ? 'PASS' : 'FAIL');
        } else checks.push({ id: 'CAP foreign key', result: 'BLOCKED', reason: 'reviewed existing foreign fixture required' });
        if (options.capOwnKey && operator && !empty) {
            await check('CAP own key', cap + 'InvoiceIntegrationStatus(' + options.capOwnKey + ')', s => s === 200 ? 'PASS' : 'FAIL');
        }
        for (const entity of ['KsefInboundDocuments', 'KsefInboundAccessGrants', 'SapResultWrites']) {
            await check('CAP private ' + entity, cap + entity, s => [403, 404].includes(s) ? 'PASS' : 'FAIL');
        }
        await check('RAP metadata', '/rap-ui/$metadata', s => options.mode === 'no-h01-role'
            ? [200, 403].includes(s) ? 'OBSERVED_ONLY' : 'FAIL' : s === 200 ? 'PASS' : 'FAIL');
        for (const entity of ['Invoices', 'InvoiceItems', 'InvoiceReferences']) {
            await check('RAP ' + entity + ' count/list', '/rap-ui/' + entity + '?$top=1&$count=true', (s, d) => {
                if (options.mode === 'no-h01-role') return s === 403 || s === 200 && d?.['@odata.count'] === 0
                    && d?.value?.length === 0 ? 'PASS' : 'FAIL';
                return s === 200 && Array.isArray(d?.value) ? 'OBSERVED_ONLY' : 'FAIL';
            });
        }
        for (const [key, entity] of [['rapForeignKey', 'Invoices'], ['rapItemForeignKey', 'InvoiceItems'],
            ['rapReferenceForeignKey', 'InvoiceReferences']]) {
            if (options[key]) await check('RAP foreign key ' + entity, '/rap-ui/' + entity + '(' + options[key] + ')',
                s => [403, 404].includes(s) ? 'PASS' : 'FAIL');
            else checks.push({ id: 'RAP foreign key ' + entity, result: 'BLOCKED', reason: 'reviewed existing foreign fixture required' });
        }
        if (options.rapOwnKey && options.mode !== 'no-h01-role') {
            await check('RAP own key', '/rap-ui/Invoices(' + options.rapOwnKey + ')', s => s === 200 ? 'PASS' : 'FAIL');
        }
        return { kind: 'LIVE_EXISTING_BROWSER_SESSION_READ_ONLY', mode: options.mode, at: new Date().toISOString(), checks,
            principal: 'BLOCKED: correlate timestamp/request ID in H01; same-origin response is not principal evidence',
            isolation: 'PENDING: compare two least-privilege sessions and reviewed nonempty fixtures',
            logoutAndExpiry: 'PENDING: run deniedAfterSessionEnd after explicit logout or idle expiry' };
    }

    /** Wywołać po logout/expiry; nigdy nie kończy sesji ani nie podąża do IdP. */
    async function deniedAfterSessionEnd() {
        if (location.origin !== expectedOrigin) throw new Error('Wrong origin');
        const checks = [];
        for (const path of [cap + 'InvoiceIntegrationStatus?$top=1', '/rap-ui/Invoices?$top=1']) {
            try {
                const r = await fetch(path, { method: 'GET', credentials: 'same-origin', redirect: 'manual',
                    signal: AbortSignal.timeout(15000), headers: { 'X-Requested-With': 'XMLHttpRequest' } });
                await r.body?.cancel();
                checks.push({ path, status: r.status, result: r.type === 'opaqueredirect' || [302, 303, 401].includes(r.status) ? 'PASS' : 'FAIL' });
            } catch { checks.push({ path, result: 'BLOCKED' }); }
        }
        return { checks, note: 'Confirms AppRouter session denial only; does not prove IdP global logout' };
    }
    globalThis.ainvSecurityAcceptance = Object.freeze({ run, deniedAfterSessionEnd });
})();
