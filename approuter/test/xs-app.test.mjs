// Kontrakt bezpieczeństwa routingu approutera (CAP-07). Test statyczny, bez sieci i bez BTP.
// Pilnuje granic z ADR-0005/ADR-0006: CAP i RAP wymagają zalogowanego business usera,
// RAP nigdy nie korzysta z systemowej Destination AINV_H01, a UI nie dostaje Basic/technicznego konta.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const xsApp = JSON.parse(readFileSync(join(root, 'xs-app.json'), 'utf8'));
const mta = readFileSync(join(root, '..', 'mta.yaml'), 'utf8');
const routeFor = (path) => xsApp.routes.find((route) => new RegExp(route.source).test(path));

test('każda trasa jest jawnie uwierzytelniana; bez Basic i bez anonimowych danych', () => {
  assert.equal(xsApp.authenticationMethod, 'route');
  for (const route of xsApp.routes) {
    assert.ok(['xsuaa', 'none'].includes(route.authenticationType), route.source);
    if (route.authenticationType === 'none') {
      // Jedyny wyjątek: publiczne biblioteki UI5 z CDN, bez danych aplikacji.
      assert.equal(route.destination, 'ui5', route.source);
      assert.match(route.source, /^\^\/resources\//);
    }
  }
});

test('ścieżki SAPUI5 trafiają do właściwych backendów', () => {
  const cap = routeFor('/odata/v4/KsefService/InvoiceIntegrationStatus');
  assert.equal(cap.destination, 'ainvoicer-cap');
  assert.equal(cap.authenticationType, 'xsuaa');
  assert.equal(cap.csrfProtection, true);
  assert.equal(cap.target, '/odata/v4/KsefService/$1');

  const rap = routeFor('/rap-ui/Invoices');
  assert.equal(rap.authenticationType, 'xsuaa');
  assert.equal(rap.target, '/sap/opu/odata4/sap/zui_ainv_inv_h_o4/srvd/sap/zui_ainv_inv_h_o4/0001/$1');
  // Trasa UI używa destination z propagacją business usera, nigdy communication usera CAP.
  assert.notEqual(rap.destination, 'AINV_H01');
  assert.equal(rap.destination, 'AINV_H01_UI_USER');

  const ui = routeFor('/index.html');
  assert.equal(ui.localDir, 'resources');
  assert.equal(ui.authenticationType, 'xsuaa');
  assert.equal(xsApp.routes.at(-1), ui, 'catch-all musi być ostatni');
});

test('brak trasy proxy do Web API H01 i do konsoli H2', () => {
  for (const path of ['/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/', '/h2-console/']) {
    const route = routeFor(path);
    assert.equal(route.localDir, 'resources', `${path} może trafić wyłącznie do statycznego UI`);
  }
});

test('MTA: token użytkownika przekazywany tylko do CAP; brak sekretów; usługi jawnie zadeklarowane', () => {
  // CAP-07B LIVE: w properties modułu nierozwiązane ~{srv-url} trafiało do approutera dosłownie (crash).
  assert.match(mta, /name: ainvoicer-cap\s+(#[^\n]*\n\s+)*url: ~\{srv-api\/srv-url\}\s+forwardAuthToken: true/);
  assert.doesNotMatch(mta, /url: ~\{srv-url\}/);
  assert.equal([...mta.matchAll(/forwardAuthToken: true/g)].length, 1, 'token XSUAA wyłącznie do CAP');
  assert.match(mta, /service: xsuaa\s+service-plan: application/);
  assert.match(mta, /path: \.\/cap-java\/xs-security\.json/);
  assert.match(mta, /SPRING_PROFILES_ACTIVE: cf/);
  // CAP-07.1: trwała baza CAP to HDI SAP HANA Cloud (deklaracja, bez deployu); brak innych usług kosztowych.
  // \r?\n: checkout z core.autocrlf=true (Windows) ma CRLF; test nie może zależeć od końców linii.
  const services = [...mta.matchAll(/service: ([\w-]+)\r?\n/g)].map((match) => match[1]).sort();
  assert.deepEqual(services, ['destination', 'hana', 'xsuaa']);
  assert.match(mta, /service: hana\s+service-plan: hdi-shared/);
  assert.match(mta, /name: ainvoicer-srv[\s\S]*?requires:\s+- name: ainvoicer-auth\s+- name: ainvoicer-destination\s+- name: ainvoicer-db/);
  assert.doesNotMatch(mta, /clientsecret|password:|BEGIN (RSA )?PRIVATE KEY/i);
  assert.doesNotMatch(mta, /ainv\.(ksef|sap-write)\.enabled:\s*true/i);
  assert.match(mta, /AINV_KSEF_ENABLED: "false"/);
  assert.match(mta, /AINV_SAP_WRITE_ENABLED: "false"/);
  assert.doesNotMatch(mta, /identity-smoke|IDENTITY_SMOKE/i, 'tryb identity-smoke wymaga osobnej zgody i nie jest w deskryptorze');
});

test('MTA: XSUAA redirect tylko na host approutera, bez wildcard na współdzieloną domenę', () => {
  assert.doesNotMatch(mta, /https:\/\/\*\./);
  assert.match(mta, /host: \$\{ainv-host-prefix\}-approuter/);
  assert.match(mta, /redirect-uris:\s+- https:\/\/\$\{ainv-host-prefix\}-approuter\.\$\{default-domain\}\/\*\*/);
});

test('MTA: hosty, redirect i xsappname nie zależą od nazwy org (spacje/podkreślenia w BTP)', () => {
  // CAP-07B: org "National Center for Cryptography_dev-v2xzij9s" dawałby nieprawidłowy host i redirect URI.
  assert.doesNotMatch(mta, /^[^#\n]*\$\{org\}/m, '${org} poza komentarzem jest zabronione');
  assert.match(mta, /ainv-host-prefix: ainvoicer-\$\{space\}/);
  assert.match(mta, /host: \$\{ainv-host-prefix\}-srv/);
  assert.match(mta, /xsappname: ainvoicer-\$\{space\}/);
  const ext = readFileSync(join(root, '..', 'deploy', 'btp-dev-eu20.mtaext'), 'utf8');
  assert.match(ext, /extends: ainvoicer/);
  assert.match(ext, /ainv-host-prefix: ainvoicer-v2xzij9s/);
  assert.match(ext, /ainv-host-prefix: [a-z0-9-]+\r?\n/, 'host: tylko małe litery, cyfry i myślniki');
  assert.doesNotMatch(ext, /identity-smoke|IDENTITY_SMOKE|clientsecret|password/i, 'rozszerzenie bez trybu smoke i sekretów');
});

test('MTA: approuter pakuje wariant UI5 CF (rap, bez testów), bez node_modules i testów konfiguracji', () => {
  assert.match(mta, /npm run build:cf --prefix sapui5\/ainvoicer/);
  assert.match(mta, /cpSync\('\.\.\/sapui5\/ainvoicer\/dist-cf','resources'/);
  assert.doesNotMatch(mta, /sapui5\/ainvoicer\/dist'/, 'nie kopiować builda developerskiego (dataMode=mock, test/)');
  assert.match(mta, /ignore: \["node_modules\/", "test\/", "default-env\.json", "default-services\.json", "\*\.log"\]/);
});

test('RAP UI: bez przekazywania tokenu CAP i bez CSRF approutera; trasa Legacy /sap nie istnieje', () => {
  const rap = routeFor('/rap-ui/Invoices');
  assert.equal(rap.forwardAuthToken, undefined);
  assert.equal(rap.csrfProtection, false, 'CSRF obsługuje backend SAP (x-csrf-token)');
  for (const path of ['/sap/opu/odata4/sap/zui_fi_acdoca_o4/srvd/sap/zui_fi_acdoca_o4/0001/$metadata', '/sap/bc/lrep/flex/data/x',
    '/odata/v4/OtherService/Entities', '/odata/v4/ksefservice/InvoiceIntegrationStatus', '/test-resources/sap/ui/qunit/x.js']) {
    const route = routeFor(path);
    assert.equal(route.localDir, 'resources', `${path} nie może trafić do żadnego backendu`);
    assert.equal(route.destination, undefined);
  }
});
