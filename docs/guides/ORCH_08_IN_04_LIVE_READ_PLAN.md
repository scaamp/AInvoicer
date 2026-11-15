---
title: "ORCH-08 — osobna bramka pierwszego GET XML"
date: 2026-11-15
type: guide
status: approval-pending
tags: [ainvoicer, orch-08, ksef, inbound, live-gate]
---

# Pierwszy GET XML — plan bez wykonania

[Raport ORCH-08](../journal/2026-11-15-orch-08-ksef-inbound-storage-e2e-integration.md) · [Pełny runbook](KSEF_IN_04_ONE_SHOT_LIVE_READ.md).

GO do przygotowania zgody. Wykonanie NO-GO bez osobnej zgody na dokładny SHA i jeden przebieg:
TEST, NIP `1234563218`, NUMBER `1234563218-20261029-7DC8A1C00000-CD`, XADES TEST, maksymalnie 7 żądań.
Parametry są propozycją. Nie obejmują BUYER, SEND ani session API.

Właściciel potwierdza dopuszczalność testowego samopodpisanego XAdES w tym kontekście TEST.
Harness generuje materiał w pamięci; nie importuje certyfikatu PROD. InvoiceRead pozostaje NOT VERIFIED
do odpowiedzi chronionego API. Odmowa nie upoważnia do zmiany credentiali, kontekstu lub uprawnień.
Czysty checkout zatwierdzonego SHA, JDK25/Maven3.9.16/SDK3.0.29, regresja offline PASS.
Nowy terminal i JVM Surefire; bez wire logging/debug, profilu Spring i flag LIVE SEND/SAP.

Poniższą komendę wykonuje się wyłącznie po osobnej zgodzie:

```powershell
Set-Location D:\AInvoicer-orch-08\cap-java
if (-not [string]::IsNullOrWhiteSpace($env:AINV_KSEF_TOKEN)) { throw 'STOP: XADES requires no token' }
$env:AINV_KSEF_ONESHOT_ENVIRONMENT = 'TEST'
$env:AINV_KSEF_NIP = '1234563218'
$env:AINV_KSEF_ONESHOT_APPROVED_NIP = '1234563218'
$env:AINV_KSEF_ONESHOT_SCENARIO = 'NUMBER'
$env:AINV_KSEF_ONESHOT_NUMBER = '1234563218-20261029-7DC8A1C00000-CD'
$env:AINV_KSEF_ONESHOT_AUTH_METHOD = 'XADES'
$env:AINV_KSEF_ONESHOT_MAX_REQUESTS = '7'
try {
    $env:AINV_KSEF_ONESHOT_AUTH_APPROVED = 'true'
    $env:AINV_KSEF_ONESHOT_READ_APPROVED = 'true'
    mvn -B test '-Dtest=KsefInboundOneShotLiveTest' `
      '-Dsurefire.failIfNoSpecifiedTests=false' `
      '-Dainv.excludedGroups=live-sap,live-sap-write' `
      '-DargLine=-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' `
      '-Dcds.cds.skipVersionCheck=true'
    if ($LASTEXITCODE -ne 0) { throw 'STOP: no automatic retry' }
} finally {
    @('AINV_KSEF_ONESHOT_AUTH_APPROVED','AINV_KSEF_ONESHOT_READ_APPROVED',
      'AINV_KSEF_ONESHOT_ENVIRONMENT','AINV_KSEF_ONESHOT_APPROVED_NIP',
      'AINV_KSEF_ONESHOT_SCENARIO','AINV_KSEF_ONESHOT_NUMBER',
      'AINV_KSEF_ONESHOT_AUTH_METHOD','AINV_KSEF_ONESHOT_MAX_REQUESTS','AINV_KSEF_NIP') |
      ForEach-Object { Remove-Item -LiteralPath "Env:$_" -ErrorAction SilentlyContinue }
}
```

Budżet 7 = challenge + XAdES + maks. 3 statusy + redeem + jeden GET.
Dekorator odmawia ósmego żądania przed I/O. Flagi JDK obowiązują od startu JVM;
test ORCH-08 potwierdza pojedyncze żądanie po zerwaniu odpowiedzi na loopback.
Bez flag JDK ten sam SDK wysyła ponowienie pod dekoratorem. Loopback nie potwierdza InvoiceRead LIVE.

Raport: SHA, run UUID, TEST/NUMBER, auth/InvoiceRead, HTTP, licznik, wynik, rozmiar, SHA-256,
metadataHash=NOT_CHECKED i FA3_BASIC_PASS (nie pełne XSD). Bez XML/NIP/numerów/JWT/kluczy/URL-i.
BUILD SUCCESS z pominiętym testem nie oznacza PASS.

| Zdarzenie | Działanie |
| --- | --- |
| 401 | STOP; ocena istniejącego credentiala/kontekstu, bez fallbacku |
| 403 | STOP; InvoiceRead DENIED, bez nadawania uprawnień |
| 404 lub MF 400/21164 | STOP; bez wyboru innego numeru |
| 429 | STOP; zachować retryNotBefore z Retry-After; nowa próba wymaga nowej zgody i upływu terminu |
| timeout/EOF/redeem bez odpowiedzi | STOP niejednoznaczny; bez ponownego auth/redeem/GET |
| zły XML/hash/schema/budżet | STOP; bez kolejnej faktury |

Brak DB, zapisu XML do HANA/pliku, workerów, schedulera i endpointów.
