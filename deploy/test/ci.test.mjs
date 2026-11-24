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
