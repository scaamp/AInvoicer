import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateConsent } from '../scripts/hana-integration.mjs';
const safe = () => ({ AINV_HANA_TEST_CONSENT: 'WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI',
    AINV_HANA_TEST_RUN_ID: '12345678-1234-1234-1234-123456789abc', AINV_HANA_TEST_NIP: '1234567802',
    AINV_HANA_TEST_PHASE: 'check', AINV_HANA_TEST_HDI_NAME: 'ainvoicer-db-test',
    VCAP_SERVICES: JSON.stringify({ hana: [{ label: 'hana', name: 'ainvoicer-db-test', plan: 'hdi-shared',
        credentials: { schema: 'synthetic', user: 'synthetic', password: 'synthetic-test-only' } }] }) });
test('HANA harness refuses absent consent, CI and incorrect binding before Maven', () => {
    assert.throws(() => validateConsent({})); assert.doesNotThrow(() => validateConsent(safe()));
    for (const key of Object.keys(safe())) { const env = safe(); delete env[key]; assert.throws(() => validateConsent(env), key); }
    assert.throws(() => validateConsent({ ...safe(), GITHUB_ACTIONS: 'true' }));
    assert.throws(() => validateConsent({ ...safe(), VCAP_SERVICES: safe().VCAP_SERVICES.replace('"synthetic","password"', '"DBADMIN","password"') }));
});
