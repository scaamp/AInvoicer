// Oficjalny emulator + SDK Azure, konto wygenerowane wyłącznie w pamięci; bez BTP i realnych credentiali.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { BlobServiceClient, StorageSharedKeyCredential, ContainerSASPermissions, generateBlobSASQueryParameters } from '@azure/storage-blob';

const here = dirname(fileURLToPath(import.meta.url)), root = resolve(here, '../..');
const port = await new Promise(resolve => { const server = createServer(); server.listen(0, '127.0.0.1', () => {
    const value = server.address().port; server.close(() => resolve(value));
}); });
const account = 'devstoreaccount1', container = 'ainv-int03', key = randomBytes(32).toString('base64');
const base = `http://127.0.0.1:${port}/${account}`;
const clean = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(AINV_|VCAP_|SPRING_|CDS_|DESTINATIONS$)/i.test(k)));
const emulator = spawn(process.execPath, [join(here, 'node_modules/azurite/dist/src/blob/main.js'),
    '--blobHost', '127.0.0.1', '--blobPort', String(port), '--inMemoryPersistence', '--silent'],
    { env: { ...clean, AZURITE_ACCOUNTS: `${account}:${key}` }, stdio: 'ignore' });
try {
    emulator.on('error', () => {});
    let ready = false;
    for (let i = 0; i < 100; i++) {
        try { await fetch(base, { signal: AbortSignal.timeout(500) }); ready = true; break; } catch {}
        if (emulator.exitCode !== null) break;
        await delay(100);
    }
    assert.ok(ready, 'local Azurite unavailable');
    const credential = new StorageSharedKeyCredential(account, key);
    await new BlobServiceClient(base, credential).getContainerClient(container).create();
    const sas = generateBlobSASQueryParameters({ containerName: container, permissions: ContainerSASPermissions.parse('racwl'),
        startsOn: new Date(Date.now() - 60000), expiresOn: new Date(Date.now() + 30 * 60000), version: '2021-08-06' }, credential).toString();
    // Tylko env procesu testów; nie zapisujemy SAS ani klucza do pliku/logu.
    const result = await new Promise(resolve => {
        const child = spawn('mvn', ['-B', '-f', 'cap-java/pom.xml', 'test', '-Dcds.cds.skipVersionCheck=true',
            '-Dtest=AzuriteCompatibilityTest', '-Dsurefire.failIfNoSpecifiedTests=false'], { cwd: root,
            shell: process.platform === 'win32', stdio: 'inherit', env: { ...clean,
                AINV_AZURITE_CONTAINER_URI: `${base}/${container}`, AINV_AZURITE_SAS: sas } });
        child.on('error', () => resolve(1)); child.on('close', resolve);
    });
    assert.equal(result, 0, 'Azurite contract tests failed');
} finally {
    if (emulator.exitCode === null) {
        const done = new Promise(resolve => emulator.once('close', resolve)); emulator.kill(); await done;
    }
}
