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

test('MTA: token użytkownika przekazywany tylko do CAP; brak sekretów i usług kosztowych', () => {
  assert.match(mta, /name: ainvoicer-cap\s+url: ~\{srv-url\}\s+forwardAuthToken: true/);
  assert.match(mta, /service: xsuaa\s+service-plan: application/);
  assert.match(mta, /path: \.\/cap-java\/xs-security\.json/);
  assert.match(mta, /SPRING_PROFILES_ACTIVE: cf/);
  // Tylko xsuaa i destination; bez HANA/usług kosztowych w tym sprincie i bez sekretów.
  const services = [...mta.matchAll(/service: ([\w-]+)\n/g)].map((match) => match[1]).sort();
  assert.deepEqual(services, ['destination', 'xsuaa']);
  assert.doesNotMatch(mta, /clientsecret|password:|BEGIN (RSA )?PRIVATE KEY/i);
  assert.doesNotMatch(mta, /ainv\.(ksef|sap-write)\.enabled:\s*true/i);
});
