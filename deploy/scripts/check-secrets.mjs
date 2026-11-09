// Kontrola wzorców w śledzonych plikach; raportuje tylko ścieżkę i rodzaj, nigdy wartość sekretu.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { root } from './identity-smoke.mjs';
const files = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
const patterns = [/-----BEGIN [A-Z ]*PRIVATE KEY-----/, /\bsk-[A-Za-z0-9_-]{24,}/,
    /\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}/];
const failures = [];
for (const file of files) {
    // Zastany vendor zawiera przykłady dotenv, w tym fikcyjne klucze; nie jest kodem wdrażanego produktu.
    if (file.split('/').includes('node_modules')) continue;
    if (/(^|\/)\.env(?:$|\.(?!example$|sample$|template$))|\.(p12|pfx|jks|key|pem)$/i.test(file)) failures.push(file);
    if (/\.(js|mjs|java|json|ya?ml|xml|md|ps1|properties|html|txt)$/.test(file)) {
        const text = readFileSync(join(root, file), 'utf8');
        if (patterns.some(p => p.test(text))) failures.push(file);
    }
}
if (failures.length) { console.error('Secret-pattern review required in: ' + [...new Set(failures)].join(', ')); process.exit(1); }
console.log('Tracked secret-pattern scan PASS (' + files.length + ' files; heuristic, not a credential audit)');
