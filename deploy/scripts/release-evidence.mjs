// Dowód integralności lokalnego, niewdrożonego MTAR. Nie potwierdza zgodności runtime CF.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import Zip from 'adm-zip';
import { root, verifyArchive } from './identity-smoke.mjs';

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

/** Hash obejmuje całą paczkę i payload modułów; nazwy i checksumy bez credentiali. */
export function hashes(file) {
    const bytes = readFileSync(file);
    const archive = new Zip(bytes);
    return { mtar: sha256(bytes), descriptor: sha256(archive.readFile('META-INF/mtad.yaml')),
        capJar: sha256(archive.readFile('ainvoicer-srv/data.zip')),
        approuterPayload: sha256(archive.readFile('ainvoicer-approuter/data.zip')) };
}

function main() {
    const artifact = join(root, 'deploy/build/ainvoicer_0.7.2.mtar');
    const file = join(root, 'deploy/build/release-evidence.json');
    const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
    const inputsFile = join(root, 'deploy/build/release-inputs.json');
    if (process.argv[2] === 'start') {
        mkdirSync(join(root, 'deploy/build'), { recursive: true });
        writeFileSync(inputsFile, JSON.stringify({ sourceCommit: git(['rev-parse', 'HEAD']),
            sourceTree: git(['rev-parse', 'HEAD^{tree}']), workingTreeDirty: git(['status', '--porcelain']).length > 0,
            sourceFilesSha256: sourceHash(git) }, null, 2) + '\n');
        console.log('Release inputs recorded before build'); return;
    }
    verifyArchive(artifact);
    if (process.argv[2] === 'verify') {
        const evidence = JSON.parse(readFileSync(file, 'utf8'));
        if (JSON.stringify(evidence.sha256) !== JSON.stringify(hashes(artifact))) throw new Error('RELEASE_HASH_MISMATCH');
        if (evidence.sourceFilesSha256 !== sourceHash(git)) throw new Error('RELEASE_SOURCE_CHANGED');
        console.log('Release hashes PASS; undeployed artifact only'); return;
    }
    if (process.argv[2] !== 'record') throw new Error('expected record or verify');
    const inputs = JSON.parse(readFileSync(inputsFile, 'utf8'));
    if (inputs.sourceFilesSha256 !== sourceHash(git)) throw new Error('RELEASE_SOURCE_CHANGED_DURING_BUILD');
    const report = { kind: 'UNDEPLOYED_IDENTITY_SMOKE', at: new Date().toISOString(),
        ...inputs,
        // Zmienione/untracked pliki są liczone do hasha; nie zapisujemy ich treści ani diffu.
        sha256: hashes(artifact), node: process.version,
        liveSecurityAcceptance: 'PENDING: see docs/guides/CAP_07D_SECURITY_ACCEPTANCE.md',
        deploymentApproved: false, h2: 'ephemeral; no business data retention or rollback recovery' };
    writeFileSync(file, JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report, null, 2));
}

/** Lista Git bez build/node_modules; deterministyczny hash realnych wejść i narzędzi tego wydania. */
export function sourceHash(git) {
    const files = git(['ls-files', '--cached', '--others', '--exclude-standard', '-z', '--',
        'cap-java', 'approuter', 'sapui5/ainvoicer', 'deploy', 'mta.yaml']).split('\0').filter(Boolean).sort();
    const hash = createHash('sha256');
    for (const file of files) {
        if (file.split('/').includes('node_modules')) continue;
        hash.update(file + '\0'); hash.update(readFileSync(join(root, file))); hash.update('\0');
    }
    return hash.digest('hex');
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try { main(); } catch { console.error('Release evidence FAILED; no deployment performed'); process.exitCode = 1; }
}
