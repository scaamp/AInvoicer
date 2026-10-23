# KSeF 2.0 TEST — Node.js + demo Java

Runner Node.js 20+ steruje REST aplikacji `AInvoicer/demo-web-app/` na 8082.
Natywny `fetch`, zero zależności npm i dodatkowych serwerów Node.
XAdES, certyfikat testowy, AES-256-CBC, RSA-OAEP SHA-256, hashe i połączenia
z KSeF pozostają w [oficjalnym SDK Java MF](https://github.com/CIRFMF/ksef-client-java).

**E2E z 23.10.2026: SUCCESS.** Numer KSeF:
`1234563218-20261023-588D70400000-42`. Faktura i sesja mają status 200,
UPO pobrano. [Raport weryfikacji](VERIFICATION.md).

## Uruchomienie

Wymagane: Node 20+, JDK 21 i oficjalne SDK podłączone zgodnie z
[README Javy](../../demo-web-app/README.md).
Z katalogu AInvoicer, w pierwszym terminalu:

```powershell
cd demo-web-app
./gradlew.bat :test :bootJar
java -jar ./build/libs/demo-web-app-3.0.29.jar --server.port=8082 --spring.profiles.active=local --sdk.config.base-uri=https://api-test.ksef.mf.gov.pl --sdk.config.suffix-uri=v2
```

W drugim terminalu, z katalogu AInvoicer:

```powershell
cd tools/ksef-java-e2e
# Tylko przy pierwszej konfiguracji; zachowaj istniejący .env.
Copy-Item .env.example .env
npm run check
npm test
node ksef-runner.js
node ksef-runner.js --send
```

Nie potrzeba `npm install`. Bez flag runner wykonuje OPTIONS, odczyt konfiguracji
JVM oraz porównanie szablonu z fixture. Nie wykonuje auth, open ani send.

**W tym workspace wysyłka została już wykonana.** Trwały znacznik blokuje
drugie `--send`. Ponowny odczyt wyniku:

```powershell
node ksef-runner.js --resume
```

## Konfiguracja

`.env` jest czytany obok skryptu; zmienne procesu mają pierwszeństwo.

| Zmienna | Domyślnie / znaczenie |
| --- | --- |
| `JAVA_CLIENT_URL` | `http://localhost:8082`; tylko loopback HTTP |
| `KSEF_ENV` | `TEST`; inne wartości odrzucane |
| `KSEF_NIP` | `1234563218`; tylko syntetyczny NIP z zadania |
| `JAVA_CLIENT_SOURCE_DIR` | `../..` w przykładzie, czyli AInvoicer; względem skryptu |
| `POLL_INTERVAL_MS` | `2000` |
| `MAX_POLL_ATTEMPTS` | `20` na etap odczytu |
| `POLL_TIMEOUT_MS` | `120000` na etap odczytu |
| `HTTP_TIMEOUT_MS` | `30000`, wraz z pobraniem odpowiedzi |
| `AUTH_HTTP_TIMEOUT_MS` | `120000`, ponieważ Java wykonuje cały auth w jednym żądaniu |

Przed auth runner odczytuje `/e2e/config` z działającej JVM. Wymaga dokładnie
`environment=TEST`, `baseUri=https://api-test.ksef.mf.gov.pl`, `suffixUri=v2`.
Brak endpointu, DEMO lub PROD blokuje operacje niezależnie od zawartości `.env`.
Starsza kopia demo uruchomiona z innego katalogu nie zawiera nowych endpointów.

## Kontrakt REST

`{s}` to referencja sesji, `{i}` referencja faktury. Ścieżki KSeF mają prefiks
`https://api-test.ksef.mf.gov.pl/v2`. Porównano je z
[OpenAPI TEST](https://api-test.ksef.mf.gov.pl/docs/v2/openapi.json).

| Etap | Lokalny REST Java | SDK → KSeF | Wynik |
| --- | --- | --- | --- |
| Preflight | `GET /e2e/config` | Bez połączenia KSeF | Efektywne środowisko JVM, bez sekretów |
| 1. Auth | `POST /authenticationProcess/{nip}` | `POST /auth/challenge`, `POST /auth/xades-signature`, polling `GET /auth/{ref}`, jeden `POST /auth/token/redeem` | `accessToken`, `refreshToken`, końcowy `status.code=200` |
| 2. XML | Brak osobnego REST | Bez połączenia KSeF | Podgląd oficjalnego FA(3) |
| 3. Otwarcie | `POST /open-session` | `POST /sessions/online`; SDK pobiera też klucze MF | `referenceNumber`, `validUntil` |
| 4. Wysyłka | `POST /send-invoice/{s}/{nip}` | `POST /sessions/online/{s}/invoices` | Referencja faktury |
| 5. Status faktury | `GET /invoice-status?referenceNumber={s}&invoiceReferenceNumber={i}` | `GET /sessions/{s}/invoices/{i}` | `status.code`, `ksefNumber` |
| 6. Zamknięcie | `POST /close-session/{s}` | `POST /sessions/online/{s}/close` | Puste HTTP 200; SDK wymaga KSeF HTTP 204 |
| 7. Status sesji | `GET /session-status?referenceNumber={s}` | `GET /sessions/{s}` | `status.code`, `upo.pages` |
| 7. UPO faktury | `GET /invoice-upo-by-invoice-number?referenceNumber={s}&invoiceReferenceNumber={i}` | `GET /sessions/{s}/invoices/{i}/upo` | Bajty XML UPO |

Do REST Javy przekazujemy `Authorization: <surowy JWT>`; SDK dodaje `Bearer `.
HTTP kontrolerów i biznesowy `status.code` to osobne informacje. Java zwykle
zwraca HTTP 200 również dla operacji przyjętych przez KSeF jako 202.
Swagger `/v3/api-docs` i `/swagger-ui/index.html` nie są skonfigurowane.

## Przebieg i interpretacja

1. Java generuje certyfikat testowy i podpis XAdES. Przy statusie auth 100 czeka,
   po 200 wykonuje jeden redeem. Node wymaga biznesowego 200 i niewygasłego JWT.
   Tokeny pozostają w pamięci; Node nie sprawdza samodzielnie podpisu JWT.
2. Fixture to podgląd syntetycznego przykładu SDK, z NIP-em `1234563218`, datą
   `2025-06-15` i numerem `E2E-PREVIEW`. Java czyta zasób classpath, nadając UUID
   podczas send. Nie przyjmuje XML w body, więc fixture nie jest dokładną kopią
   wysłanych bajtów. Szablon porównujemy po podstawieniach. Fixture przeszedł
   walidację [oficjalnym XSD FA(3)](https://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd)
   wraz z importami; pełna walidacja XSD nie jest uruchamiana przy każdym starcie.
3. Java otwiera sesję, generuje AES/IV, szyfruje klucz RSA i przygotowuje FA(3).
4. Przed jednym send Node atomowo zapisuje `output/send-attempt.json`.
   Java szyfruje XML, liczy hashe i wysyła fakturę. Utrata odpowiedzi albo 500
   oznacza nieznany wynik; send nie jest ponawiany.
5. Status faktury 100/150 oznacza oczekiwanie, 200 wymaga numeru KSeF,
   440 oznacza duplikat. Odrzucenie nie jest sukcesem.
6. Runner podejmuje jedną próbę close także po błędzie wysyłki/statusu.
   `CLOSE_ACCEPTED` potwierdza żądanie; przetworzenie sesji sprawdza etap 7.
7. Przy statusie sesji 100/170 runner czeka na 200 oraz dostępne `upo.pages`.
   Następnie pobiera UPO faktury przez Javę do `output/upo.xml`. Nie korzysta
   bezpośrednio z podpisanych URL-i. Sprawdza rozpoznawalny XML, bez weryfikacji
   kryptograficznej podpisu UPO. Brak UPO w limicie daje `PENDING`.

Odczyty ponawiają przejściowe awarie sieci, 429 i 5xx w limicie prób/czasu.
401/403, odrzucenie i naruszenie kontraktu kończą etap. Auth/redeem, send
i niejednoznaczny close nie mają automatycznego retry.

## Wyniki i powtórzenia

`output/result.json` zawiera referencje, statusy, numer KSeF i wynik UPO,
bez JWT, refreshToken, kluczy i podpisanych URL-i. `.env`, `output/`, materiały
kluczy i `node_modules/` są ignorowane przez Git.

`--resume` wykonuje nowy auth i odczyty istniejącej faktury/sesji. Nie otwiera
sesji ani nie wysyła dokumentu. Może podjąć jedną niepodjętą wcześniej próbę close,
ale nie powtarza close o nieznanym wyniku. Przy nieznanym send bez referencji
faktury trzeba najpierw uzgodnić stan w KSeF/Java; nie usuwaj znacznika send.

`output/runner.lock` blokuje równoległe uruchomienia. Po awarii usuń go dopiero
po sprawdzeniu, że runner nie działa. Demo nadal ma wspólne `encryptionData`:
nie wykonuj równolegle innych wysyłek przez tę instancję Javy.

Kody wyjścia: `0` — udany preflight lub pełny `SUCCESS`; `1` — błąd;
`2` — oczekiwanie na sesję/UPO. Nowy eksperyment wymaga świadomego zarchiwizowania
poprzednich wyników po potwierdzeniu ich stanu.
