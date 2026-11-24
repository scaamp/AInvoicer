import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import YAML from 'yaml';
import { root } from '../scripts/identity-smoke.mjs';

test('CI has read-only permissions, no credentials and no live mutation commands', () => {
    const text = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8');
    const workflow = YAML.parse(text);
    assert.deepEqual(workflow.permissions, { contents: 'read' });
    assert.equal(workflow.jobs.offline.env.AINV_H01_LIVE_WRITE, 'false');
    assert.equal(workflow.jobs.offline.env.AINV_KSEF_ENABLED, 'false');
    assert.equal(workflow.jobs.offline.env.AINV_SAP_WRITE_ENABLED, 'false');
    const commands = workflow.jobs.offline.steps.map(s => s.run || '').join('\n');
    assert.doesNotMatch(commands, /\bcf\s+(deploy|push|restart|restage|create-service)|AINV_H01_LIVE_WRITE=true|excludedGroups=none|\$\{\{\s*secrets\./);
    assert.ok(workflow.jobs.offline.steps.some(s => s.with?.['java-version'] === '25'));
    assert.ok(workflow.jobs.offline.steps.some(s => s.with?.['node-version'] === '22'));
});

test('CI requires all three CF builds in sequence with artifact verification in the build', () => {
    const { steps } = YAML.parse(readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')).jobs.offline;
    const ui5Builds = steps.filter(step => /^npm run build:cf(?: |:inbox-beta )/.test(step.run || ''));
    assert.deepEqual(ui5Builds.map(step => step.run), [
        'npm run build:cf --prefix sapui5/ainvoicer',
        'npm run build:cf:inbox-beta --prefix sapui5/ainvoicer',
        'npm run build:cf:inbox-beta --prefix sapui5/ainvoicer -- --with-xml-download'
    ]);
    for (const step of ui5Builds) {
        assert.equal(step.if, undefined);
        assert.equal(step['continue-on-error'], undefined);
    }
});

test('BETA-02: CI builds and verifies both complete Beta MTARs after the HANA candidate and smokes the local UAT', () => {
    const { steps } = YAML.parse(readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')).jobs.offline;
    const index = predicate => steps.findIndex(step => predicate(step.run || ''));
    const hana = index(run => run.includes('npm run verify:stor04-release --prefix deploy'));
    const beta = index(run => run.includes('npm run build:btp:beta --prefix deploy'));
    assert.ok(hana >= 0 && beta > hana, 'beta MTAR requires the verified 0.7.4 candidate first');
    assert.deepEqual(steps[beta].run.trim().split('\n').map(l => l.trim()), [
        'npm run build:btp:beta --prefix deploy',
        'npm run verify:beta --prefix deploy',
        'npm run build:btp:beta --prefix deploy -- --with-xml-download',
        'npm run verify:beta --prefix deploy -- --with-xml-download'
    ]);
    const uat = index(run => run === 'npm run start:beta-uat --prefix sapui5/ainvoicer -- --smoke');
    assert.ok(uat > index(run => run.includes('test:inbox:e2e')), 'UAT smoke runs after the Inbox E2E');
    for (const step of [steps[beta], steps[uat]]) {
        assert.equal(step.if, undefined);
        assert.equal(step['continue-on-error'], undefined);
    }
});
