---
title: "KSeF TEST — odtworzenie auth i wysyłki CAP"
date: 2026-10-29
type: guide
status: aktywny
tags: [ainvoicer, ksef, cap-java, test, runbook]
---

# KSeF TEST — runbook

[Integracja SDK](KSEF_JAVA_SDK_INTEGRATION.md) · [Dziennik](../journal/2026-10-29-agent-a-ksef-sdk.md)

Wszystkie komendy dotyczą PowerShell i `feature/ksef-auth`. Środowisko SDK jest
ograniczone do `https://api-test.ksef.mf.gov.pl/v2`. Użytkownik zlecił maksymalnie
jedną nową syntetyczną fakturę w tej sesji. Wznowienie ma korzystać z tej samej operacji.

## Przygotowanie SDK bez PAT

```powershell
Set-Location D:\AInvoicer-auth
.\cap-java\tools\Install-KsefSdk.ps1 `
  -SourceDirectory D:\ksef-sdk-v3.0.29 `
  -Jdk21 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
Set-Location .\cap-java
mvn clean verify '-Dcds.cds.skipVersionCheck=true'
```

SDK build wymaga JDK 21 dla wrappera Gradle 8.8. CAP build wymaga JDK 25 i Maven
≥3.9.14. Skrypt przywraca `JAVA_HOME`. Nie kopiuje SDK do Git ani nie uruchamia demo.
Opcja CDS pomija tylko sprawdzanie wersji narzędzia; kompilacja i walidacja CDS działają.

## Jawny test auth — bez otwierania sesji i wysyłki

```powershell
Set-Location D:\AInvoicer-auth\cap-java
$env:AINV_KSEF_LIVE_AUTH = 'true'
$env:AINV_KSEF_NIP = '1234563218' # syntetyczny kontekst eksperymentu TEST
mvn test '-Dtest=KsefTestApiLiveTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dainv.excludedGroups=live-sap,live-sap-write' `
  '-Dcds.cds.skipVersionCheck=true'
Remove-Item Env:AINV_KSEF_LIVE_AUTH
```

Test wybiera token z `AINV_KSEF_TOKEN`, jeśli już jest bezpiecznie skonfigurowany;
w przeciwnym razie używa samopodpisanego XAdES TEST przez SDK. Nie wpisuj tokenów
w historii poleceń. Wynik wypisuje wyłącznie PASS etapów. Sam JWT pozostaje w JVM.

## Potwierdzony E2E i odczyt po restarcie

W tym workspace **wykonano już jedną nową wysyłkę**. Operacja:
`b2d7ced6-1c2d-4f5a-8414-294dd3f67831`, numer:
`1234563218-20261029-7DC8A1C00000-CD`. UPO: 5528 bajtów, root `Potwierdzenie`,
zawiera ten numer i SHA-256 wysłanego XML. Nie sprawdzano kryptograficznego podpisu UPO.
Źródłem był oficjalny syntetyczny fixture SDK i lokalna migawka, nie LIVE H01.

Dokładne polecenia odczytu tej samej operacji po restarcie:

```powershell
Set-Location D:\AInvoicer-auth\cap-java
$env:AINV_KSEF_LIVE_SEND = 'true' # bramka harnessu; tryb poniżej wybiera RESUME
$env:AINV_KSEF_SYNTHETIC = 'true'
$env:AINV_KSEF_NIP = '1234563218'
$env:AINV_KSEF_E2E_DIR = 'D:/AInvoicer-ksef-test-20261029-agent-a'
$env:AINV_KSEF_E2E_MODE = 'RESUME'
mvn test '-Dtest=KsefOnlineE2ELiveTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dainv.excludedGroups=live-sap,live-sap-write' `
  '-Dcds.cds.skipVersionCheck=true'
Get-Content "$env:AINV_KSEF_E2E_DIR/result.json"
Remove-Item Env:AINV_KSEF_LIVE_SEND
```

`RESUME` wymaga istniejącej bazy i markera. Nie tworzy nowej operacji i odmawia
zainicjowania wysyłki z PREPARED. COMPLETED odczytuje lokalnie, bez auth/send/close.
Dla przerwanego send szuka istniejącej faktury po hashu. `closeAttempted` blokuje
powtórzenie niejednoznacznego close. Nie usuwaj bazy ani `send-attempt.json`.

## Przygotowanie kolejnego, osobno uzgodnionego eksperymentu

Poniższe komendy opisują nowy eksperyment; nie wykonuj ponownie SEND w bieżącej
operacji. Skrypt przygotowuje oficjalny fixture, nie zastępuje generatora FA(3)
Agenta B. Wymaga Pythona z lxml (`python -m pip install lxml`, jeśli brak).

```powershell
Set-Location D:\AInvoicer-auth\cap-java
# Uzgodniony, nowy katalog. Obecny katalog ma marker i zatrzyma przygotowanie.
$env:AINV_KSEF_E2E_DIR = 'D:/AInvoicer-ksef-test-20261029-agent-a'
python tools/Prepare-OfficialKsefFixture.py `
  --sdk D:/ksef-sdk-v3.0.29 --directory $env:AINV_KSEF_E2E_DIR `
  --nip 1234563218 --issue-date 2026-10-29
if ($LASTEXITCODE -ne 0) { throw 'Fixture preparation stopped' }
$env:AINV_KSEF_XML = "$env:AINV_KSEF_E2E_DIR/invoice.xml"
$env:AINV_KSEF_VALIDATED_SHA256 = (Get-Content "$env:AINV_KSEF_E2E_DIR/validated-sha256.txt" -Raw).Trim()
$env:AINV_KSEF_LIVE_SEND = 'true'
$env:AINV_KSEF_SYNTHETIC = 'true'
$env:AINV_KSEF_NIP = '1234563218'
$env:AINV_KSEF_E2E_MODE = 'SEND'
mvn test '-Dtest=KsefOnlineE2ELiveTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dainv.excludedGroups=live-sap,live-sap-write' `
  '-Dcds.cds.skipVersionCheck=true'
Remove-Item Env:AINV_KSEF_LIVE_SEND
```

Skrypt pobiera 4 oficjalne XSD z CRD (HTTPS), zapisuje hash oryginałów, zmienia
wyłącznie importy na lokalne pliki i wykonuje ścisłą walidację lxml. Harness
sprawdza SHA-256 dokładnych bajtów oraz zgodność NIP sprzedawcy przed siecią.
JAXP z JDK przekracza limit złożoności pełnego schematu; dlatego harness oficjalnego
fixture korzysta z niezależnej, ścisłej walidacji lxml i jej digestu.

Harness używa prawdziwego workera CAP i plikowej H2, z wyłączonym collectorem;
sam wywołuje ten sam worker zgodnie z utrwalonym retryNotBefore. Natywny dispatch
i restart collectora potwierdzają testy lokalne. Marker CREATE_NEW powstaje przed
pierwszym HTTP. Błąd po markerze wymaga RESUME/rekoncyliacji, nie nowego SEND.

W konfiguracji aplikacji transport wymaga jawnego `ainv.ksef.enabled=true`, NIP,
credentiala token/XAdES oraz wybranego storage. Domyślne wartości nie wykonują sieci.
Żaden z tych parametrów nie włącza `ainv.sap-write.enabled`.

Jeśli odpowiedź send/close zaginie: zachowaj bazę i pliki, odczytaj
`KsefSubmissionWorker.status(id)`, potem `reconcile(id)` w kontekście backendu.
Ta metoda nie wysyła faktury ponownie. Przy braku dowodu pozostaw stan wymagający
rekoncyliacji. Nie usuwaj markerów ani nie twórz nowego klucza idempotencji.
