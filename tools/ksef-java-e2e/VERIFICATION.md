# Raport weryfikacji — 23.10.2026

**Rzeczywisty E2E: SUCCESS, środowisko KSeF TEST.**

| Pole | Wynik |
| --- | --- |
| Początek próby | 23.10.2026, 12:35:37 Europe/Warsaw |
| NIP testowy | `1234563218` |
| Auth | HTTP 200, biznesowy status 200, uzyskano JWT |
| Sesja | `20261023-SO-245F02A000-448405100C-97` |
| Referencja faktury | `20261023-EE-245F108000-AE6B8626C7-05` |
| Status faktury | 150 → 200 |
| Numer KSeF | **`1234563218-20261023-588D70400000-42`** |
| Close | `CLOSE_ACCEPTED`, HTTP 200 z Javy |
| Status sesji | 200, `PROCESSED` |
| UPO sesji | Jedna strona w `upo.pages` |
| UPO faktury | `DOWNLOADED`, 5520 bajtów |
| Kod wyjścia | 0 |

UPO pobrano przez REST Javy. Parser `lxml` potwierdził poprawność XML,
korzeń `{http://upo.schematy.mf.gov.pl/KSeF/v4-3}Potwierdzenie` i obecność tego
numeru KSeF. Nie wykonywano weryfikacji kryptograficznej podpisu UPO.

Pliki `output/result.json`, `output/upo.xml`, `output/send-attempt.json` są
ignorowane przez Git. Znacznik send zachowano. Nie wykonano drugiej wysyłki.
Ponowne odczyty: `node ksef-runner.js --resume`.

## Testy

| Sprawdzenie | Wynik |
| --- | --- |
| Node.js / JDK | `v24.19.0` / `21.0.7` |
| `npm run check` | PASS |
| `npm test` | **22/22 PASS** |
| `demo-web-app/gradlew.bat :test :bootJar` | BUILD SUCCESSFUL, **20/20 testów demo PASS** |
| Testy SDK przy pierwszym buildzie | **16/16 PASS** |
| Fixture FA(3), oficjalny XSD z importami | VALID, `lxml 6.0.0` |
| Preflight `OPTIONS` + `Allow` | 7/7 PASS |
| `GET /e2e/config` | TEST, `https://api-test.ksef.mf.gov.pl`, `v2` |

Testy kontrolera obejmują 100 → 100 → 200, pojedynczy redeem, odrzucenie,
limit prób/czasu, brak statusu, przerwanie, brak retry błędu redeem, blokadę
samopodpisanego auth w PROD, REST statusu sesji i efektywną konfigurację JVM.
Testy Node obejmują transport, timeout body, polling, duplikat, blokadę send,
resume bez drugiej wysyłki i blokadę niezgodnego środowiska JVM.

## Zakres

Branch: `feature/ksef-java-e2e-runner`. Poprawiono wskazany folder
`AInvoicer/demo-web-app/`. Oficjalne SDK z lokalnego repozytorium MF, commit
`dc6cb26c3f879e69439230e930fe96efcf3c9c49`, pozostało bez zmian źródłowych.
Podłącza je `KSEF_SDK_DIR` lub `-PksefSdkDir`.

- Auth ma jawną ograniczoną pętlę odczytu i zwraca końcowy status biznesowy.
- Dodano `/session-status` oraz `/e2e/config`.
- XML jest czytany z classpath, także wewnątrz JAR-a.
- Runner sprawdza działającą JVM, status sesji i dostępność UPO.
- Kryptografia i komunikacja z KSeF pozostały w SDK MF.

Pierwsza próba o 12:14 zakończyła się `StatusWaitingException` przed wysyłką.
Po poprawkach wykonano powyższą pojedynczą wysyłkę zakończoną sukcesem.
Na 8082 działa teraz JAR z `AInvoicer/demo-web-app/` z jawnym adresem TEST.
Nie używano DEMO ani PROD. Istniejące zmiany `.gitignore`, ABAP i
`ksef-client-csharp/` zachowano. Nie wykonano commita ani publikacji.

Źródła: lokalne kontrolery, [SDK Java MF](https://github.com/CIRFMF/ksef-client-java),
[OpenAPI TEST](https://api-test.ksef.mf.gov.pl/docs/v2/openapi.json),
[XSD FA(3)](https://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd).
