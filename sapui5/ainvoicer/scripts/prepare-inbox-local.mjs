// Osobna, ignorowana kopia UI5; źródłowy manifest mock i transformacja CF pozostają niezależne.
import { cpSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
const root = fileURLToPath(new URL("../", import.meta.url));
const target = join(root, "build", "inbox-webapp");
mkdirSync(target, { recursive: true });
cpSync(join(root, "webapp"), target, { recursive: true });
const manifest = JSON.parse(readFileSync(join(target, "manifest.json"), "utf8"));
manifest["sap.ui5"].config.ksefInbox = {
    dataMode: "cap", pageSize: 2, environments: ["TEST"],
    cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview" },
    capabilities: { viewXml: false, checkDuplicate: false, linkSapDocument: false, approveAndPost: false }
};
writeFileSync(join(target, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log("Prepared local Inbox CAP profile (sync OFF; CF unaffected)");
