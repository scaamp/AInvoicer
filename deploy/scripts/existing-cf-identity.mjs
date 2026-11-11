// Jawny test negatywny własnego CAP: używa istniejącego logowania CF, nie wystawia tokenów/credentiali.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { verifyTarget } from './cf-readiness.mjs';
import { probe } from './live-security.mjs';

/** Tylko dwa GET na zatwierdzony direct CAP, bez redirectu, eksportu Authorization lub nowego grantu. */
export async function checkExistingCfIdentity(config, fetcher = fetch) {
    verifyTarget(config);
    if (!/^bearer\s+\S+$/i.test(config.AccessToken || '')) throw new Error('CF_EXISTING_IDENTITY_UNAVAILABLE');
    const checks = [];
    for (const path of ['$metadata', 'InvoiceIntegrationStatus?$top=1']) {
        checks.push(await probe({ id: 'Existing CF user token outside CAP identity: ' + path,
            url: 'https://ainvoicer-v2xzij9s-srv.cfapps.eu20-001.hana.ondemand.com/odata/v4/KsefService/' + path,
            authorization: config.AccessToken, expected: [401] }, fetcher));
    }
    return { kind: 'LIVE_EXISTING_FOREIGN_IDENTITY_READ_ONLY', at: new Date().toISOString(), checks,
        note: 'Existing Cloud Controller user token only; no token issued/exported; rejection does not isolate audience from signature/issuer checks.' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        const config = JSON.parse(readFileSync(join(process.env.CF_HOME || join(homedir(), '.cf'), 'config.json'), 'utf8'));
        const report = await checkExistingCfIdentity(config);
        const out = fileURLToPath(new URL('../build/foreign-identity.json', import.meta.url));
        mkdirSync(dirname(out), { recursive: true }); writeFileSync(out, JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report, null, 2));
        if (report.checks.some(c => c.result !== 'PASS')) process.exitCode = 2;
    } catch { console.error('Existing CF identity check BLOCKED; raw output suppressed'); process.exitCode = 2; }
}
