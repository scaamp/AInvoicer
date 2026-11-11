// Regresja błędu LIVE 30401273: assemble nie tłumaczy typów projektowych na typy deploymentu CF.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import YAML from 'yaml';
import { descriptor as smoke, loadMta, root, validate as validateSmoke } from '../scripts/identity-smoke.mjs';
import { descriptor as hana, validate as validateHana } from '../scripts/hana-readiness.mjs';

test('assembled CF module types match the pinned SAP MBT mtad-gen including the reviewed extension', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ainv-cf-types-'));
    try {
        const result = spawnSync(process.execPath, [join(root, 'deploy/node_modules/mbt/bin/mbt'),
            'mtad-gen', '-p', 'cf', '-s', root, '-t', dir,
            '-e', join(root, 'deploy/btp-dev-eu20-hana-first.mtaext')], { encoding: 'utf8' });
        assert.equal(result.status, 0, 'pinned SAP MBT must accept the development descriptor and extension');
        const official = YAML.parse(readFileSync(join(dir, 'mtad.yaml'), 'utf8'));
        for (const variant of [smoke(loadMta()), hana()]) {
            for (const module of variant.modules) {
                const expected = official.modules.find(m => m.name === module.name);
                assert.equal(module.type, expected.type, module.name);
                assert.equal(expected.parameters.stack, 'cflinuxfs4', module.name);
            }
        }
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('CF archive validation rejects project types that caused the LIVE merge failure', () => {
    for (const [factory, validate] of [[() => smoke(loadMta()), validateSmoke], [hana, validateHana]]) {
        for (const source of loadMta().modules) {
            const variant = factory();
            const module = variant.modules.find(m => m.name === source.name);
            if (!module) continue;
            module.type = source.type;
            assert.throws(() => validate(variant), source.name + ': ' + source.type);
        }
    }
});
