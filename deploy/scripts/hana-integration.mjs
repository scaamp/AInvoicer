// Osobno zatwierdzony harness REAL HANA. Ten plik nie tworzy usług, bindingów ani credentiali.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { root } from './identity-smoke.mjs';

export function validateConsent(env) {
    assert.notEqual(env.GITHUB_ACTIONS, 'true', 'Real HANA tests cannot run in GitHub Actions');
    assert.equal(env.AINV_HANA_TEST_CONSENT, 'WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI', 'Separate owner consent required');
    assert.match(env.AINV_HANA_TEST_RUN_ID || '', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    assert.match(env.AINV_HANA_TEST_NIP || '', /^[0-9]{10}$/);
    assert.ok(['check', 'seed', 'recover', 'contend', 'cleanup'].includes(env.AINV_HANA_TEST_PHASE));
    assert.ok(['ainvoicer-db', 'ainvoicer-db-test'].includes(env.AINV_HANA_TEST_HDI_NAME));
    const services = JSON.parse(env.VCAP_SERVICES || '{}');
    const hana = Object.values(services).flat().filter(b => b.label === 'hana');
    assert.equal(hana.length, 1, 'Exactly one managed HDI binding required');
    assert.equal(hana[0].plan, 'hdi-shared');
    assert.equal(hana[0].name, env.AINV_HANA_TEST_HDI_NAME);
    assert.ok(hana[0].credentials?.schema && hana[0].credentials?.user && hana[0].credentials?.password);
    assert.ok(!/^DBADMIN$/i.test(hana[0].credentials.user), 'DBADMIN is forbidden');
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        validateConsent(process.env);
        const result = spawnSync('mvn', ['-B', '-f', 'cap-java/pom.xml', 'verify', '-Phana-integration',
            '-Dcds.cds.skipVersionCheck=true'], { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
        process.exitCode = result.status ?? 1;
    } catch { console.error('HANA harness BLOCKED: consent, synthetic scope and managed HDI binding required; no deployment performed'); process.exitCode = 1; }
}
