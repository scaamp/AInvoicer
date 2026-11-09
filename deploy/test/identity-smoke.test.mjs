import { test } from 'node:test';
import assert from 'node:assert/strict';
import YAML from 'yaml';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { descriptor, loadMta, root, validate, smokeEnv } from '../scripts/identity-smoke.mjs';

test('deterministic schema-valid descriptor, no HANA, existing XSUAA and Destination only', () => {
    const source = loadMta();
    const before = YAML.stringify(source);
    const first = YAML.stringify(descriptor(source));
    assert.equal(first, YAML.stringify(descriptor(loadMta())));
    assert.equal(YAML.stringify(source), before, 'source HANA descriptor unchanged');
    validate(YAML.parse(first));
    assert.ok(source.resources.some(r => r.name === 'ainvoicer-db'));
});
test('every smoke invariant and unknown env fail closed', () => {
    for (const key of Object.keys(smokeEnv)) {
        const mta = descriptor(loadMta());
        mta.modules[0].properties[key] = key === 'AINV_DEPLOYMENT_IDENTITY_SMOKE' ? 'false' : 'true';
        assert.throws(() => validate(mta), key);
    }
    for (const mutate of [m => m.modules[0].properties.SECRET = 'unexpected',
        m => m.resources.push({ name: 'paid-service' }),
        m => m.modules[0].requires.push({ name: 'ainvoicer-db' }),
        m => m.modules[1].properties.destinations[0].url = '~{srv-url}',
        m => m.modules[0].parameters.host = '${org}-srv']) {
        const mta = descriptor(loadMta()); mutate(mta); assert.throws(() => validate(mta));
    }
    const unsafeSource = loadMta(); unsafeSource.modules[0].properties.AINV_KSEF_ENABLED = 'true';
    assert.throws(() => descriptor(unsafeSource));
});
test('EU20 extension keeps valid DNS hosts even for org containing spaces', () => {
    const ext = YAML.parse(readFileSync(join(root, 'deploy/btp-dev-eu20.mtaext'), 'utf8'));
    assert.equal(ext.extends, descriptor(loadMta()).ID);
    assert.match(ext.parameters['ainv-host-prefix'], /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/);
    for (const m of descriptor(loadMta()).modules) {
        const host = m.parameters.host.replace('${ainv-host-prefix}', ext.parameters['ainv-host-prefix']);
        assert.match(host, /^[a-z0-9-]{1,63}$/);
    }
});
