// Osobna, ignorowana kopia UI5; źródłowy manifest mock i transformacja CF pozostają niezależne.
// BETA-01: sekcja ksefInbox pochodzi z tej samej transformacji co artefakt `build:cf:inbox-beta`
// (read-only CAP InboundInvoiceOverview, viewXml OFF), więc lokalny E2E sprawdza dokładnie konfigurację bety.
// Jedyna różnica: pageSize=2, aby 5 syntetycznych rekordów wymusiło paginację.
import { cpSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { transformManifest } from "./build-cf.mjs";
const root = fileURLToPath(new URL("../", import.meta.url));
const target = join(root, "build", "inbox-webapp");
mkdirSync(target, { recursive: true });
cpSync(join(root, "webapp"), target, { recursive: true });
const manifest = JSON.parse(readFileSync(join(target, "manifest.json"), "utf8"));
const betaInbox = transformManifest(manifest, { variant: "inbox-beta" })["sap.ui5"].config.ksefInbox;
manifest["sap.ui5"].config.ksefInbox = { ...betaInbox, pageSize: 2 };
writeFileSync(join(target, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log("Prepared local Inbox CAP profile from the inbox-beta transform (sync OFF, viewXml OFF; CF unaffected)");
