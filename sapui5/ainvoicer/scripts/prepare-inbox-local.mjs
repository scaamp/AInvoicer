// Osobna, ignorowana kopia UI5; źródłowy manifest mock i transformacja CF pozostają niezależne.
// BETA-01: sekcja ksefInbox pochodzi z tej samej transformacji co artefakt `build:cf:inbox-beta`
// (read-only CAP InboundInvoiceOverview, viewXml OFF), więc lokalny E2E sprawdza dokładnie konfigurację bety.
// Jedyna różnica: pageSize=2, aby 5 syntetycznych rekordów wymusiło paginację.
// BETA-02: `--with-xml-download` (wyłącznie lokalny UAT) = transformacja `build:cf:inbox-beta -- --with-xml-download`.
import { cpSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { transformManifest, parseBuildArgs } from "./build-cf.mjs";
const root = fileURLToPath(new URL("../", import.meta.url));
const target = join(root, "build", "inbox-webapp");
// Te same reguły CLI co build CF: wyłącznie znane flagi; wariant zawsze inbox-beta.
const options = parseBuildArgs(["--variant=inbox-beta", ...process.argv.slice(2)]);
mkdirSync(target, { recursive: true });
cpSync(join(root, "webapp"), target, { recursive: true });
const manifest = JSON.parse(readFileSync(join(target, "manifest.json"), "utf8"));
const betaInbox = transformManifest(manifest, options)["sap.ui5"].config.ksefInbox;
manifest["sap.ui5"].config.ksefInbox = { ...betaInbox, pageSize: 2 };
writeFileSync(join(target, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log("Prepared local Inbox CAP profile from the inbox-beta transform (sync OFF, viewXml "
    + (options.xmlDownload ? "ON" : "OFF") + "; CF unaffected)");
