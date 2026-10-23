---
name: ainvoicer-ksef
description: "Implementuj i diagnozuj KSeF 2.0 w AInvoicer przez oficjalny Java SDK MF: auth/XAdES/token, FA(3), sesje online/batch, wysyłkę, statusy, numer KSeF, UPO, odbiór i rekoncyliację. Nie używaj do samego CRUD CAP lub zmian RAP. Użycie Skill nie upoważnia do wysyłki TEST ani działań PROD."
---

# AInvoicer — KSeF 2.0

## Granice

Przeczytaj [AGENTS.md](../../../AGENTS.md), [STATUS](../../../docs/STATUS.md), ROADMAP, właściwy dziennik i [ADR-0001](../../../docs/decisions/ADR-0001-cap-java-ksef-architecture.md). Sprawdź branch, `git status`, istniejące integracje, POM i testy. Linki są względem tego pliku.

Używaj biblioteki `CIRFMF/ksef-client-java` w tej samej JVM co CAP Java/Spring Boot, za interfejsem aplikacji. Nie kopiuj całej `demo-web-app`, kontrolerów ani wspólnego stanu sesji. Node/Python pozostają funkcjami AI, ABAP adapterem SAP. Eksperyment na `feature/ksef-java-e2e-runner` jest referencją; sprawdź dostępność i audytuj wybrane fragmenty bez przełączania/niszczenia pracy użytkownika. Nie zakładaj obecności runnera ani SDK na bieżącej gałęzi.

## Workflow

1. **Ustal scenariusz i granice efektów.** Wybierz wysyłkę online, batch, odbiór zakupów, status/UPO lub rekoncyliację. Pierwszy vertical slice to jedna syntetyczna faktura SAP Remote OData → CAP → SDK → KSeF TEST → numer KSeF i UPO. Batch, zakupy, korekty, offline i multi-NIP wdrażaj w zleconym zakresie. Przed zmianą podaj plan i kryteria akceptacji.
2. **Sprawdź oficjalny kontrakt i wersje.** Ustal środowisko TEST/DEMO/PROD, rzeczywisty base URL i wersję API/OpenAPI, przewodnika MF, XSD FA(3) i wydania/commita SDK. Zweryfikuj sygnatury, modele odpowiedzi, błędy, limity i testy SDK. Przypnij zależność i sprawdź zgodność JDK/Spring Boot/CAP; nie zgaduj Maven coordinates ani metod. Jeśli Swagger jest niedostępny dla narzędzia, odczytaj oficjalny kontrakt źródłowy i odnotuj brak weryfikacji wdrożenia.
3. **Zaprojektuj trwały proces.** Rozdziel dane faktury, operację, kierunek i status techniczny/biznesowy. Zapisuj środowisko, kontekst uprawnień, korelację SAP ↔ operacja ↔ sesja ↔ invoice reference ↔ numer KSeF, audyt bez sekretów oraz idempotency key z trwałą unikalnością. Nie zakładaj, że MF obsługuje dowolny nagłówek idempotencji. Zdefiniuj konkurencję i przejścia stanów przed wysyłką; `status.code` interpretuj według konkretnej operacji API, nie jak HTTP status.
4. **Uwierzytelnij właściwy kontekst.** Przez SDK obsłuż challenge → XAdES lub token KSeF → polling statusu auth → redeem → AccessToken/RefreshToken i odświeżanie według kontraktu. Sam NIP/`contextId` w payload nie daje uprawnienia. Zweryfikuj prawo użytkownika do kontekstu. Oddziel środowiska i credentiale; JWT oraz kryptografia pozostają w backendzie. Samopodpisany XAdES tylko tam, gdzie dopuszcza go TEST, nigdy jako założenie dla PROD.
5. **Przygotuj i wyślij.** Waliduj dane SAP i XML FA(3) według oficjalnego XSD, oddzielając poprawność biznesową SAP od technicznego przyjęcia MF. Używaj SDK do aktualnych certyfikatów MF, ochrony klucza AES przez RSA-OAEP SHA-256, szyfrowania AES-256-CBC/PKCS#7 i skrótów SHA-256; nie pisz własnej kryptografii, jeśli SDK ją zapewnia. AES/IV, JWT i sesja nie mogą być współdzielonym mutowalnym stanem singletona. Akcja OData atomowo zapisuje operację/zlecenie, trwały worker/outbox wykonuje efekty po commit, poza transakcją żądania. Utrwal referencję odpowiedzi przed dalszymi etapami.
6. **Doprowadź do rozstrzygnięcia.** Monitoruj końcowy biznesowy status faktury i numer KSeF. HTTP 202 nie potwierdza przyjęcia faktury. Zamknij sesję i pobierz UPO według statusu i faktycznej dostępności. XML/UPO umieść w wybranym trwałym magazynie, z metadanymi/referencjami w DB, nie na lokalnym dysku CF. Oddziel przyjęcie faktury od gotowości UPO; opóźnienie UPO nie może wywołać ponownej wysyłki. Dla zakupów sprawdź filtry, role, stronicowanie/eksport, deduplikację i trwały punkt wznowienia według API zamiast używać ścieżki wysyłkowej.
7. **Obsłuż awarie bez duplikacji.** Timeout/utrata odpowiedzi send, redeem lub close prowadzi do `UNKNOWN` / `RECONCILIATION_REQUIRED` z dostępną korelacją. Nie ponawiaj automatycznie niejednoznacznego efektu, także przez retry outbox/SDK/HTTP. Najpierw uzgodnij wynik bezpiecznymi odczytami i referencjami; bez dowodu pozostaw stan wymagający rekoncyliacji. Duplikat klasyfikuj według kontraktu i potwierdzonego powiązania, nie jako automatyczny sukces. Retry ogranicz do bezpiecznych odczytów i świadomie sklasyfikowanych błędów; stosuj limit prób/czasu, backoff, jitter i 429/`Retry-After`. Trwały harmonogram ma pozwalać wznowić odczyty po restarcie i zapobiegać równoległemu wykonaniu operacji przez instancje CF.
8. **Testuj bez wysyłki domyślnej.** Przygotuj mocki SDK/HTTP i syntetyczne dane. Sprawdź auth w toku/odmowę, refresh, końcowe przyjęcie/odrzucenie, 202 bez numeru KSeF, brak/pojawienie się UPO, 429, timeout po efekcie, duplikat, konkurencję i restart. Potwierdź, że niejednoznaczny send/redeem/close nie powoduje ponownego efektu. Dla zmian CAP wykonaj odpowiednie testy i `mvn clean verify` z `cap-java/`.
9. **Live w zleconym zakresie.** Wysyłkę TEST wykonaj wyłącznie po wyraźnym zleceniu użytkownika na syntetycznych danych. Bez niego zakończ na implementacji/mockach i podaj przygotowany następny krok. Nie uruchamiaj żadnej operacji PROD bez wyraźnej autoryzacji. Dla zleconego E2E TEST potwierdź wynik końcowy, obecność numeru KSeF i faktyczne pobranie UPO; bez tych dowodów nie deklaruj pełnego sukcesu.

## Bezpieczeństwo i raport

Nie zapisuj do Git, logów ani odpowiedzi JWT, RefreshToken, tokena KSeF, kluczy AES/prywatnych, P12/PFX/JKS/PEM ani sekretów możliwych do odzyskania. Nie zwracaj JWT do SAPUI5; używaj faktycznie dostępnych zarządzanych sekretów/bindings. Autoryzuj dostęp do XML/UPO i raportuj bez poufnych danych API.

Podaj pliki, wersje/źródła kontraktu, stany operacji, polecenia i osobne wyniki mock, test lokalny oraz live. Aktualizuj STATUS/dziennik/ROADMAP według AGENTS.md; brak live nie jest PASS E2E. Do modeli, handlerów, persystencji i Remote Service dołącz [ainvoicer-cap-java](../ainvoicer-cap-java/SKILL.md), tylko jeśli zadanie wymaga tej warstwy. Nie ładuj automatycznie Skill ABAP.

## Oficjalne źródła do sprawdzenia

- [Java SDK MF](https://github.com/CIRFMF/ksef-client-java) — biblioteka i testy jako źródło sygnatur.
- [KSeF API 2.0](https://github.com/CIRFMF/ksef-api) — auth, sesje, odbiór, limity, statusy i kontrakty.
- [KSeF TEST Swagger](https://api-test.ksef.mf.gov.pl/docs/v2/index.html) — faktyczne wdrożenie; sprawdź też oficjalny odsyłacz do aktualnego XSD FA(3).
