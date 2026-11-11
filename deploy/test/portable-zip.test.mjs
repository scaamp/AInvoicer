import { test } from 'node:test';
import assert from 'node:assert/strict';
import Zip from 'adm-zip';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { normalizePermissions, verifyPermissions } from '../scripts/portable-zip.mjs';

test('Windows CF payload is normalized to Unix files 0644 and traversable directories 0755 without changing bytes', () => {
    const zip = new Zip();
    zip.addFile('package.json', Buffer.from('{"name":"synthetic"}'));
    zip.addFile('resources/', Buffer.alloc(0));
    zip.addFile('resources/index.html', Buffer.from('synthetic'));
    for (const entry of zip.getEntries()) {
        entry.header.made = 0x0a14; // Takie nagłówki miało rzeczywiste odrzucone archiwum Windows.
        entry.attr = (((entry.isDirectory ? 0o40666 : 0o100666) << 16) | (entry.isDirectory ? 0x10 : 0)) >>> 0;
    }
    assert.throws(() => verifyPermissions(zip));
    const before = zip.getEntries().map(e => [e.entryName, e.getData().toString('hex')]);
    const reread = new Zip(normalizePermissions(zip).toBuffer());
    verifyPermissions(reread);
    assert.deepEqual(reread.getEntries().map(e => [e.entryName, e.getData().toString('hex')]), before);
    const pkg = reread.getEntry('package.json');
    assert.equal(pkg.header.made >>> 8, 3);
    assert.equal((pkg.header.attr >>> 16) & 0o777, 0o644);
    assert.equal((reread.getEntry('resources/').header.attr >>> 16) & 0o777, 0o755);
});

test('serialized archive fails closed for inaccessible, writable, executable or non-Unix entries', () => {
    for (const change of [e => e.header.made = 0x0a14,
        e => e.attr = 0o100000 << 16, e => e.attr = 0o100666 << 16,
        e => e.attr = 0o100755 << 16]) {
        const zip = new Zip(); zip.addFile('package.json', Buffer.from('{}'));
        normalizePermissions(zip); change(zip.getEntry('package.json'));
        assert.throws(() => verifyPermissions(new Zip(zip.toBuffer())));
    }
    const zip = new Zip(); zip.addFile('resources/', Buffer.alloc(0)); normalizePermissions(zip);
    zip.getEntry('resources/').attr = (0o40644 << 16) | 0x10;
    assert.throws(() => verifyPermissions(new Zip(zip.toBuffer())));
});

const goAvailable = spawnSync('go', ['version'], { encoding: 'utf8' }).status === 0;
test('independent Go ZIP reader reproduces Windows mode 000 and reads normalized files/directories as 0644/0755',
    { skip: !goAvailable && process.env.GITHUB_ACTIONS !== 'true' ? 'Go unavailable locally; required on Ubuntu CI' : false }, () => {
        assert.ok(goAvailable, 'Ubuntu CI must run the independent Go ZIP consumer');
        const dir = mkdtempSync(join(tmpdir(), 'ainv-go-zip-'));
        try {
            const zip = new Zip(); zip.addFile('package.json', Buffer.from('{}'));
            zip.addFile('resources/', Buffer.alloc(0));
            for (const entry of zip.getEntries()) entry.header.made = 0x0a14;
            zip.writeZip(join(dir, 'windows.zip'));
            normalizePermissions(zip).writeZip(join(dir, 'portable.zip'));
            // Rzeczywisty archive/zip, bez emulowania jego reguł w naszym walidatorze JS.
            writeFileSync(join(dir, 'probe.go'), `package main
import ("archive/zip"; "fmt"; "os")
func perm(file, name string) uint32 {
 r, err := zip.OpenReader(file); if err != nil { panic(err) }; defer r.Close()
 for _, f := range r.File { if f.Name == name { return uint32(f.Mode().Perm()) } }
 panic("missing entry")
}
func main() {
 old := perm(os.Args[1], "package.json")
 file := perm(os.Args[2], "package.json")
 dir := perm(os.Args[2], "resources/")
 if old != 0 || file != 0644 || dir != 0755 { panic("unsafe ZIP permissions") }
 fmt.Printf("old=%o file=%o dir=%o", old, file, dir)
}
`);
            const result = spawnSync('go', ['run', join(dir, 'probe.go'), join(dir, 'windows.zip'), join(dir, 'portable.zip')],
                { cwd: dir, encoding: 'utf8', timeout: 120000, env: { ...process.env, GO111MODULE: 'off', GOTOOLCHAIN: 'local' } });
            assert.equal(result.status, 0, 'independent Go ZIP consumer failed: ' + result.stderr);
            assert.equal(result.stdout, 'old=0 file=644 dir=755');
        } finally { rmSync(dir, { recursive: true, force: true }); }
    });
