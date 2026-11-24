// BETA-02: opt-in kompletny MTAR Beta — kontrakt deskryptora (offline) i, gdy zbudowany lokalnie/CI, archiwum.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { descriptor as hanaDescriptor } from '../scripts/hana-readiness.mjs';
import { BASE, VARIANTS, artifactPath, descriptor, variantOf, verifyArchive } from '../scripts/beta-release.mjs';

test('beta descriptor differs from the reviewed 0.7.4 HANA release only by version label and description', () => {
    for (const variant of Object.keys(VARIANTS)) {
        const beta = descriptor(variant);
        const hana = hanaDescriptor();
        assert.notEqual(beta.version, hana.version);
        assert.notEqual(beta.version, '0.7.3', 'must not reuse the deployed label');
        assert.deepEqual({ ...beta, version: hana.version, description: hana.description }, hana);
        assert.deepEqual(beta.modules.map(m => m.name).sort(), ['ainvoicer-approuter', 'ainvoicer-db-deployer', 'ainvoicer-srv']);
        assert.ok(beta.resources.every(r => r.type === 'org.cloudfoundry.existing-service'), 'never creates services');
    }
    assert.notEqual(VARIANTS['inbox-beta'].version, VARIANTS['inbox-beta-xml'].version, 'distinct labels per UI content');
});

test('beta srv keeps every external effect and the new INBOUND storage/download flags OFF', () => {
    const srv = descriptor('inbox-beta-xml').modules.find(m => m.name === 'ainvoicer-srv');
    for (const [key, value] of Object.entries({ AINV_KSEF_ENABLED: 'false', AINV_KSEF_INBOUND_ENABLED: 'false',
        AINV_SAP_WRITE_ENABLED: 'false', AINV_SAP_WRITE_SWEEPER_ENABLED: 'false', AINV_DEPLOYMENT_HANA_READINESS: 'true',
        AINV_KSEF_STORAGE_PROVIDER: 'off' })) {
        assert.equal(srv.properties[key], value, key);
    }
    assert.ok(!JSON.stringify(srv.properties).match(/XML_DOWNLOAD|STORAGE_HANA|MANUAL_IMPORT|SEND/i), 'no opt-in flag in the release');
});

test('variant arguments are explicit and fail closed', () => {
    assert.equal(variantOf([]), 'inbox-beta');
    assert.equal(variantOf(['--with-xml-download']), 'inbox-beta-xml');
    assert.throws(() => variantOf(['--with-send']));
});

test('built beta MTAR (when present locally) keeps srv/HDI bytes of 0.7.4 and passes UI5 beta rules', async (t) => {
    for (const variant of Object.keys(VARIANTS)) {
        if (!existsSync(artifactPath(variant)) || !existsSync(BASE)) { t.skip('beta MTAR not built in this checkout'); return; }
        const report = await verifyArchive(artifactPath(variant), variant);
        assert.equal(report.srvAndHdiIdenticalToBase, true);
        assert.deepEqual(report.hdi.changed, []);
        assert.deepEqual(report.hdi.removed, []);
        assert.equal(report.hdi.added.length, 1);
    }
});
