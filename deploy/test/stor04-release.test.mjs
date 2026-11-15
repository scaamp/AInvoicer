// KSEF-STOR-04R: weryfikator artefaktu release względem wdrożonego payloadu HDI. Offline, bez CF/HANA.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import Zip from 'adm-zip';
import YAML from 'yaml';
import { verifyRelease, loadJson, BASELINE_FILE, CONTRACT_FILE, CANDIDATE, ADDED } from '../scripts/stor04-release.mjs';

const require = createRequire(import.meta.url);
const sha256 = b => createHash('sha256').update(b).digest('hex');
const baseline = loadJson(BASELINE_FILE);
const contract = loadJson(CONTRACT_FILE);

test('committed deployed baseline describes the 13.11 payload: 15 tables, 5 views, 6 indexes, recorded hashes', () => {
    assert.equal(baseline.mtaVersion, '0.7.3');
    assert.equal(baseline.mtarSha256, 'b30117cf2ef0d212e0c001d4eda765f919632eeac726089d776405ef1cbf947d');
    assert.equal(baseline.hdiPayloadSha256, 'b772fb4b8f9840e9bab2f8a9e042dd9fb98414afc320edd5abc9cdc8f015ba4f');
    assert.equal(baseline.evidence, 'OFFLINE_FROM_LOCAL_COPY_OF_DEPLOYED_MTAR');
    const files = Object.keys(baseline.hdiFiles);
    assert.equal(files.length, 28);
    assert.equal(files.filter(f => f.endsWith('.hdbtable')).length, 15);
    assert.equal(files.filter(f => f.endsWith('.hdbview')).length, 5);
    assert.equal(files.filter(f => f.endsWith('.hdbindex')).length, 6);
    assert.ok(!files.includes(ADDED), 'STOR-04 table is not deployed');
    assert.deepEqual(Object.keys(baseline.deployerPackageFilesLfSha256).sort(), ['package-lock.json', 'package.json']);
});

test('current CDS compiles to the deployed artifacts byte-for-byte plus exactly one new table', async () => {
    const cds = require('../../cap-java/node_modules/@sap/cds-dk/node_modules/@sap/cds');
    const root = fileURLToPath(new URL('../../cap-java/', import.meta.url));
    const model = await cds.load([join(root, 'db'), join(root, 'srv'), join(root, 'node_modules/@sap/cds-dk/node_modules/@sap/cds/srv/outbox.cds')]);
    const artifacts = Object.fromEntries([...cds.compile.to.hana(model, { sqlMapping: 'plain' })].map(([sql, f]) => ['src/gen/' + f.file, sha256(sql)]));
    const deployed = Object.fromEntries(Object.entries(baseline.hdiFiles).filter(([n]) => !/\/\.hdi(config|namespace)$/.test(n)));
    const added = Object.keys(artifacts).filter(n => !(n in deployed));
    const changed = Object.keys(deployed).filter(n => n in artifacts && artifacts[n] !== deployed[n]);
    const removed = Object.keys(deployed).filter(n => !(n in artifacts));
    assert.deepEqual({ added, changed, removed }, { added: [ADDED], changed: [], removed: [] });
    assert.equal(artifacts[ADDED], contract.storageDdlSha256);
    assert.equal(contract.storageDdlSha256, '27cac1a20896f4e7971198c72e2024d48fa3e7f1e5f3d93c2e7ed2a77304de6a');
    // Deployer package bez zmian treści względem wdrożonego (porównanie po normalizacji końców linii).
    for (const name of ['package.json', 'package-lock.json']) {
        const text = readFileSync(join(root, 'db', name), 'utf8').replace(/\r\n/g, '\n');
        assert.equal(sha256(Buffer.from(text, 'utf8')), baseline.deployerPackageFilesLfSha256[name], name);
    }
});

// Syntetyczny MTAR i baseline (nie z prawdziwych artefaktów): sprawdza wyłącznie logikę porównania.
const FILES = { 'src/gen/.hdiconfig': '{}', 'src/gen/a.hdbtable': 'COLUMN TABLE a (ID INTEGER, PRIMARY KEY(ID))',
    'src/gen/v.hdbview': 'VIEW v AS SELECT ID FROM a' };
const NEW = 'COLUMN TABLE customer_cap_java_KsefStoredDocuments (x INTEGER)';
const PKG = '{\n  "scripts": { "start": "node node_modules/@sap/hdi-deploy/deploy.js --use-hdb" }\n}\n';
const LOCK = '{\n  "lockfileVersion": 3\n}\n';
const synthBaseline = { version: 1, kind: 'DEPLOYED_HDI_BASELINE', mtaVersion: '0.7.3', srvPayloadSha256: sha256('srv-old'),
    approuterPayloadSha256: sha256('ar'), hdiFiles: Object.fromEntries(Object.entries(FILES).map(([n, c]) => [n, sha256(c)])),
    deployerPackageFilesLfSha256: { 'package.json': sha256(PKG), 'package-lock.json': sha256(LOCK) } };
const synthContract = { storageDdlSha256: sha256(NEW) };

function mtar({ files = { ...FILES, [ADDED]: NEW }, pkg = PKG, lock = LOCK, extra = {}, version = '0.7.4', resourceType = 'org.cloudfoundry.existing-service' } = {}) {
    const hdi = new Zip();
    for (const [n, c] of Object.entries({ ...files, 'package.json': pkg, 'package-lock.json': lock, ...extra })) hdi.addFile(n, Buffer.from(c));
    const zip = new Zip();
    zip.addFile('META-INF/mtad.yaml', Buffer.from(YAML.stringify({ ID: 'ainvoicer', version,
        modules: [{ name: 'ainvoicer-srv' }, { name: 'ainvoicer-approuter' }, { name: 'ainvoicer-db-deployer', requires: [{ name: 'ainvoicer-db' }] }],
        resources: [{ name: 'ainvoicer-db', type: resourceType }] })));
    zip.addFile('ainvoicer-db-deployer/data.zip', hdi.toBuffer());
    zip.addFile('ainvoicer-srv/data.zip', Buffer.from('srv-new'));
    zip.addFile('ainvoicer-approuter/data.zip', Buffer.from('ar'));
    return zip.toBuffer();
}

test('exactly +1 STOR-04 table, 0 changed, 0 removed -> PASS; CRLF package files tolerated; srv change reported', () => {
    const report = verifyRelease(mtar({ pkg: PKG.replace(/\n/g, '\r\n') }), synthBaseline, synthContract);
    assert.deepEqual([report.hdi.added, report.hdi.changed, report.hdi.removed, report.hdi.unchanged], [[ADDED], [], [], 3]);
    assert.equal(report.srvPayloadChanged, true, 'full MTAR would also redeploy newer CAP code');
    assert.equal(report.approuterPayloadChanged, false);
    assert.equal(report.deploymentApproved, false);
    assert.equal(report.liveHdiDeploy, 'NOT EXECUTED');
});

test('any deviation from the reviewed release scope fails closed', () => {
    const cases = {
        'changed existing table': { files: { ...FILES, 'src/gen/a.hdbtable': FILES['src/gen/a.hdbtable'] + ' ', [ADDED]: NEW } },
        'removed existing view': { files: { 'src/gen/.hdiconfig': '{}', 'src/gen/a.hdbtable': FILES['src/gen/a.hdbtable'], [ADDED]: NEW } },
        'missing STOR-04 table': { files: FILES },
        'second new artifact': { files: { ...FILES, [ADDED]: NEW, 'src/gen/x.hdbview': 'VIEW x AS SELECT 1 FROM DUMMY' } },
        'different STOR-04 DDL': { files: { ...FILES, [ADDED]: NEW + ' ' } },
        'undeploy.json': { extra: { 'undeploy.json': '["src/gen/a.hdbtable"]' } },
        'data file': { extra: { 'src/gen/a.hdbtabledata': '{}' } },
        'auto-undeploy': { pkg: PKG.replace('--use-hdb', '--use-hdb --auto-undeploy') },
        'changed lock': { lock: LOCK.replace('3', '2') },
        'unexpected payload file': { extra: { 'README.md': 'x' } },
        'same MTA version label': { version: '0.7.3' },
        'managed HDI resource (would create service)': { resourceType: 'com.sap.xs.hdi-container' },
    };
    for (const [name, options] of Object.entries(cases)) {
        assert.throws(() => verifyRelease(mtar(options), synthBaseline, synthContract), name);
    }
});

test('built candidate MTAR (when present locally/CI) adds only KsefStoredDocuments vs the deployed payload', { skip: !existsSync(CANDIDATE) && 'candidate MTAR not built in this checkout' }, () => {
    const report = verifyRelease(readFileSync(CANDIDATE));
    assert.equal(report.mtaVersion, '0.7.4');
    assert.deepEqual([report.hdi.added, report.hdi.changed, report.hdi.removed], [[ADDED], [], []]);
});
