import { test } from 'node:test';
import assert from 'node:assert/strict';
import { descriptor, validate, hanaEnv } from '../scripts/hana-readiness.mjs';

test('HANA variant uses three existing services and keeps external effects and all outboxes OFF', () => {
    const mta = descriptor(); validate(mta);
    assert.deepEqual(mta.resources.map(r => r.name).sort(), ['ainvoicer-auth', 'ainvoicer-db', 'ainvoicer-destination']);
    assert.ok(mta.resources.every(r => r.type === 'org.cloudfoundry.existing-service'));
    assert.equal(mta.modules.find(m => m.name === 'ainvoicer-db-deployer').parameters['no-route'], true);
});
test('unsafe service creation, extra bindings or changed deployment flags fail closed', () => {
    for (const key of Object.keys(hanaEnv)) {
        const mta = descriptor(); mta.modules[0].properties[key] = 'unsafe'; assert.throws(() => validate(mta), key);
    }
    for (const mutate of [m => m.resources[2].type = 'com.sap.xs.hdi-container',
        m => m.resources.push({ name: 'objectstore' }), m => m.modules[0].requires.push({ name: 'paid' }),
        m => m.modules[1].properties.SESSION_TIMEOUT = 60]) {
        const mta = descriptor(); mutate(mta); assert.throws(() => validate(mta));
    }
});
