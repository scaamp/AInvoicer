// KSEF-STOR-04R: offline weryfikacja kandydata release względem RZECZYWIŚCIE wdrożonego payloadu HDI (13.11, 0.7.3).
// Nie wykonuje cf/HANA/HDI. Sprawdza, że payload HDI zbudowanego MTAR różni się od wdrożonego DOKŁADNIE o jedną
// nową tabelę KsefStoredDocuments (hash z kontraktu STOR-04), bez zmian i usunięć istniejących artefaktów,
// bez undeploy oraz z niezmienionym package.json/lock deployera (po normalizacji końców linii).
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import Zip from 'adm-zip';
import YAML from 'yaml';

export const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
export const BASELINE_FILE = join(root, 'deploy/contracts/hdi-deployed-baseline-0.7.3.json');
export const CONTRACT_FILE = join(root, 'deploy/contracts/hdi-catalog-stor04.v1.json');
export const CANDIDATE = join(root, 'deploy/build/ainvoicer_0.7.4.mtar');
export const ADDED = 'src/gen/customer.cap_java.KsefStoredDocuments.hdbtable';
const sha256 = b => createHash('sha256').update(b).digest('hex');
const lf = b => Buffer.from(b.toString('utf8').replace(/\r\n/g, '\n'), 'utf8');

export function loadJson(file) { return JSON.parse(readFileSync(file, 'utf8')); }

/**
 * Wynik porównania. `hdi` musi być dokładnie {added:[ADDED], changed:[], removed:[]}; inne moduły są tylko
 * raportowane (zmiana srv/approutera oznacza, że PEŁNY MTAR wdrożyłby nowszy kod — patrz przewodnik STOR-04R).
 */
export function verifyRelease(mtarBuffer, baseline = loadJson(BASELINE_FILE), contract = loadJson(CONTRACT_FILE)) {
    assert.equal(baseline.kind, 'DEPLOYED_HDI_BASELINE');
    assert.equal(baseline.version, 1);
    const mtar = new Zip(mtarBuffer);
    const mtad = YAML.parse(mtar.readAsText('META-INF/mtad.yaml'));
    assert.equal(mtad.ID, 'ainvoicer');
    assert.notEqual(mtad.version, baseline.mtaVersion, 'candidate must not reuse the deployed MTA version label');
    const deployer = mtad.modules.find(m => m.name === 'ainvoicer-db-deployer');
    assert.ok(deployer, 'HDI deployer module missing');
    assert.deepEqual(deployer.requires, [{ name: 'ainvoicer-db' }]);
    const db = mtad.resources.find(r => r.name === 'ainvoicer-db');
    assert.equal(db.type, 'org.cloudfoundry.existing-service', 'release must use the existing HDI container, never create one');
    const hdiBuffer = mtar.readFile('ainvoicer-db-deployer/data.zip');
    assert.ok(hdiBuffer, 'HDI payload missing');
    const hdi = new Zip(hdiBuffer);
    const entries = hdi.getEntries().filter(e => !e.isDirectory);
    const names = entries.map(e => e.entryName);
    assert.ok(!names.some(n => /(^|\/)undeploy\.json$|\.hdbtabledata$|\.csv$|\.hdbgrants$|\.hdbrole$/i.test(n)), 'no undeploy/data/grants/roles');
    const pkg = JSON.parse(hdi.readAsText('package.json'));
    assert.ok(!JSON.stringify(pkg).includes('auto-undeploy'), 'auto-undeploy forbidden');
    for (const [name, expected] of Object.entries(baseline.deployerPackageFilesLfSha256)) {
        assert.equal(sha256(lf(hdi.readFile(name))), expected, `deployer ${name} changed vs deployed`);
    }
    const current = Object.fromEntries(entries.filter(e => e.entryName.startsWith('src/gen/')).map(e => [e.entryName, sha256(e.getData())]));
    const result = { added: [], changed: [], removed: [], unchanged: 0 };
    for (const [name, hash] of Object.entries(current)) {
        if (!(name in baseline.hdiFiles)) result.added.push(name);
        else if (baseline.hdiFiles[name] !== hash) result.changed.push(name);
        else result.unchanged++;
    }
    for (const name of Object.keys(baseline.hdiFiles)) if (!(name in current)) result.removed.push(name);
    const other = names.filter(n => !n.startsWith('src/gen/') && !(n in baseline.deployerPackageFilesLfSha256));
    assert.deepEqual(other, [], 'unexpected files in deployer payload');
    assert.deepEqual(result.removed, [], 'release would drop existing HDI artifacts');
    assert.deepEqual(result.changed, [], 'release would change existing HDI artifacts');
    assert.deepEqual(result.added, [ADDED], 'release must add exactly the STOR-04 table');
    assert.equal(current[ADDED], contract.storageDdlSha256, 'STOR-04 DDL hash differs from reviewed contract');
    return {
        mtaVersion: mtad.version, deployedMtaVersion: baseline.mtaVersion,
        hdi: { ...result, addedSha256: current[ADDED], payloadSha256: sha256(hdiBuffer) },
        srvPayloadChanged: sha256(mtar.readFile('ainvoicer-srv/data.zip')) !== baseline.srvPayloadSha256,
        approuterPayloadChanged: sha256(mtar.readFile('ainvoicer-approuter/data.zip')) !== baseline.approuterPayloadSha256,
        mtarSha256: sha256(mtarBuffer), modules: mtad.modules.map(m => m.name),
        deploymentApproved: false, liveHdiDeploy: 'NOT EXECUTED',
    };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        const [action, file, ...extra] = process.argv.slice(2);
        assert.equal(action, 'verify');
        assert.equal(extra.length, 0);
        const report = verifyRelease(readFileSync(file ? resolve(file) : CANDIDATE));
        console.log(JSON.stringify(report, null, 2));
        console.log('STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0 evidence=OFFLINE');
    } catch (error) {
        console.error('STOR04_RELEASE_ARTIFACT=FAIL ' + String(error && error.message || error).split('\n')[0].slice(0, 200));
        process.exitCode = 1;
    }
}
