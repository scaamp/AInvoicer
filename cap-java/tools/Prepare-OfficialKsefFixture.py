"""Przygotowanie oficjalnego fixture SDK do jawnego E2E TEST; nie jest generatorem domenowym FA(3)."""
import argparse
import hashlib
import json
import subprocess
import uuid
from pathlib import Path
from urllib.parse import urljoin, urlparse
from urllib.request import urlopen

from lxml import etree

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", required=True, type=Path)
parser.add_argument("--directory", required=True, type=Path)
parser.add_argument("--nip", required=True)
parser.add_argument("--issue-date", required=True)
args = parser.parse_args()
expected = "3c3a899541074aa4bd944636b25659aef7d23da7"
actual = subprocess.check_output(["git", "-C", str(args.sdk), "rev-parse", "HEAD"], text=True).strip()
if actual != expected:
    raise SystemExit("SDK_COMMIT_MISMATCH")
directory = args.directory.resolve()
directory.mkdir(parents=True, exist_ok=True)
if (directory / "send-attempt.json").exists():
    raise SystemExit("EXISTING_ATTEMPT_USE_RESUME")
if not args.nip.isdigit() or len(args.nip) != 10:
    raise SystemExit("NIP_FORMAT_INVALID")

schemas = directory / "xsd"
schemas.mkdir(exist_ok=True)
manifest, seen = [], set()


def fetch_schema(url):
    """Pobiera wyłącznie CRD; importy mapuje lokalnie, zapisując hashe oryginalnych XSD."""
    url = url.replace("http://", "https://")
    if urlparse(url).hostname != "crd.gov.pl":
        raise ValueError("XSD_HOST_NOT_ALLOWED")
    name = Path(urlparse(url).path).name
    if url in seen:
        return name
    seen.add(url)
    with urlopen(url, timeout=25) as response:
        raw = response.read(5 * 1024 * 1024)
    manifest.append({"url": url, "sha256": hashlib.sha256(raw).hexdigest()})
    document = etree.fromstring(raw, etree.XMLParser(resolve_entities=False, no_network=True))
    for node in document.xpath("//*[@schemaLocation]"):
        node.attrib["schemaLocation"] = fetch_schema(urljoin(url, node.attrib["schemaLocation"]))
    (schemas / name).write_bytes(etree.tostring(document, encoding="UTF-8", xml_declaration=True))
    return name


fetch_schema("https://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd")
(directory / "xsd-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
source = args.sdk / "demo-web-app/src/main/resources/xml/invoices/sample/invoice-template_v3.xml"
xml = source.read_text(encoding="utf-8").replace("#nip#", args.nip).replace("#invoicing_date#", args.issue_date)
xml = xml.replace("#invoice_number#", "CAP-TEST-" + str(uuid.uuid4()))
# Fixture jest syntetyczny; adresy kontaktowe kierujemy do zarezerwowanej domeny .invalid.
xml = xml.replace("Lucja17@example.net", "seller@example.invalid").replace("Jakubina82@gmail.com", "buyer@example.invalid")
exact = xml.encode("utf-8")
schema = etree.XMLSchema(etree.parse(str(schemas / "schemat.xsd")))
schema.assertValid(etree.fromstring(exact, etree.XMLParser(resolve_entities=False, no_network=True)))
digest = hashlib.sha256(exact).hexdigest()
(directory / "invoice.xml").write_bytes(exact)
(directory / "validated-sha256.txt").write_text(digest, encoding="ascii")
print(json.dumps({"xsd": "PASS", "imports": len(manifest), "xmlBytes": len(exact), "sha256": digest}))
