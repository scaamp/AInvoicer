# Demo Java KSeF w AInvoicer

Kopia `demo-web-app` z oficjalnego repozytorium MF, dostosowana do
[runnera Node](../tools/ksef-java-e2e/README.md). Podpis, kryptografię i połączenia
z KSeF realizuje oryginalne SDK.

## Budowanie i start w TEST

Wymagane: JDK 21 i lokalny checkout
[CIRFMF/ksef-client-java](https://github.com/CIRFMF/ksef-client-java).
Wrapper Gradle 8.8 skopiowano z tego repozytorium.

`settings.gradle.kts` podłącza podprojekt `ksef-client`, domyślnie z
`../../ksef-java-client/ksef-client-java/ksef-client`, względem tego folderu.
Inną lokalizację ustaw przez `-PksefSdkDir=...` albo:

```powershell
$env:KSEF_SDK_DIR='C:/sciezka/do/ksef-client-java/ksef-client'
```

Z `AInvoicer/demo-web-app`:

```powershell
./gradlew.bat :test :bootJar
java -jar ./build/libs/demo-web-app-3.0.29.jar --server.port=8082 --spring.profiles.active=local --sdk.config.base-uri=https://api-test.ksef.mf.gov.pl --sdk.config.suffix-uri=v2
```

Można też użyć `./gradlew.bat :bootRun --args="--server.port=8082 --spring.profiles.active=local --sdk.config.base-uri=https://api-test.ksef.mf.gov.pl --sdk.config.suffix-uri=v2"`.
Linux/macOS: `./gradlew` zamiast `./gradlew.bat`. Na 8082 może działać jedna instancja.
XML jest zasobem classpath; katalog roboczy nie wpływa już na odczyt szablonu.

## Poprawki demo

- Auth odpytuje status przy kodzie 100. Dopiero po 200 wykonuje jeden redeem.
  Odrzucenie daje 401, limit 504, przerwanie 503, brak statusu 502.
  Redeem i błędy sieci nie są automatycznie ponawiane.
- Odpowiedź auth zachowuje `accessToken` i `refreshToken`, dodaje `status`.
- `GET /session-status?referenceNumber=...` deleguje do `getSessionStatus` SDK.
- `GET /e2e/config` pokazuje efektywne `baseUri`, `suffixUri`, `environment`
  bez sekretów. Runner wymaga TEST przed auth/send.
- Samopodpisany auth demo jest blokowany poza adresem TEST.
- Szablony działają także wewnątrz spakowanego JAR-a.

Domyślne właściwości Spring pollingu auth: `e2e.auth.poll-interval-ms=1000`,
`e2e.auth.max-poll-attempts=20`, `e2e.auth.poll-timeout-ms=25000`.
Timeout pojedynczego HTTP kontroluje `sdk.config.request-timeout` w sekundach;
trwający odczyt może przekroczyć deadline pętli o czas jednego HTTP.

JWT do REST Javy przekazuj jako surową wartość w `Authorization`; SDK dodaje
`Bearer `. Nie loguj tokenów ani kluczy. Nie wykonuj równoległych wysyłek:
kontroler nadal ma wspólne `encryptionData`, zgodnie z ograniczeniem demo.

## Weryfikacja

23.10.2026: 20 testów demo PASS, 22 testy Node PASS, rzeczywisty E2E TEST SUCCESS.
Numer KSeF: `1234563218-20261023-588D70400000-42`. Faktura i sesja: status 200;
UPO pobrano. [Raport](../tools/ksef-java-e2e/VERIFICATION.md).
