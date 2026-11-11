// Payload CF jest rozpakowywany na Linuxie: Windows creator=10 nie ma poprawnego trybu w Go archive/zip.
import assert from 'node:assert/strict';

/** Ujednolica tylko metadane ZIP; pliki danych/JAR/JS nie wymagają bitu wykonywania. */
export function normalizePermissions(zip) {
    for (const entry of zip.getEntries()) {
        entry.header.made = 0x0300 | (entry.header.made & 0xff);
        entry.attr = (((entry.isDirectory ? 0o40755 : 0o100644) << 16) | (entry.isDirectory ? 0x10 : 0)) >>> 0;
    }
    return zip;
}

/** Odrzuca nieprzenośny creator, brak traverse katalogu i przypadkowe nadmiarowe uprawnienia. */
export function verifyPermissions(zip) {
    for (const entry of zip.getEntries()) {
        assert.equal(entry.header.made >>> 8, 3, 'CF ZIP must use Unix creator: ' + entry.entryName);
        assert.equal(entry.header.attr >>> 16, entry.isDirectory ? 0o40755 : 0o100644,
            'CF ZIP must use directories 0755 and regular files 0644: ' + entry.entryName);
    }
}
