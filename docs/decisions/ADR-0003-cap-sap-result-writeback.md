---
title: "ADR-0003 — trwały writeback potwierdzonego wyniku CAP → SAP"
date: 2026-10-28
type: adr
status: zaakceptowany
tags: [ainvoicer, cap-java, rap, outbox, idempotency]
---

# ADR-0003 — CAP writeback przez persistent outbox

[Project Home](../README.md) · [ADR-0002](ADR-0002-sap-ksef-result-write-contract.md) · [Dziennik CAP-04C](../journal/2026-10-28-cap-04c-sap-result-writeback.md) · [Hardening 2026-10-29](../journal/2026-10-29-cap-04-hardening.md)

## Context

SAP udostępnia `ApplyKsefResult`, a CAP pozostaje właścicielem procesu i trwałego zlecenia. Użytkownik zlecił commit/push CAP-04B i kontynuację CAP-04C. Zgoda nie obejmuje CAP-04D HTTP WRITE, nadania uprawnień ani wywołań KSeF.

## Decision

Importujemy rzeczywisty EDMX H01 z zachowaniem oryginalnego namespace. CAP Remote Service i wygenerowany kontekst akcji odpowiadają za HTTP, CSRF i serializację. Importowany kontrakt nadal wymaga `internal-user`. Nie wystawiamy nowej publicznej akcji do zlecania writeback.

Zaufany backend wywołuje `SapKsefResultWriteback.enqueue` w aktywnej transakcji i kontekście systemowym. Źródłowa operacja musi mieć `ACCEPTED`, `OUTBOUND`, `SEND_INVOICE`, środowisko `TEST` i potwierdzony numer. Jeśli istnieje powiązanie do faktury CAP, sprawdzamy NIP, kierunek i korelację SAP. Publiczny CREATE nadal wymusza `NEW`, a nowe readonly `environment` ma wartość `TEST`; klient nie może utworzyć potwierdzenia przez OData.

`SapResultWrites` przechowuje niezmienne polecenie i jego rozstrzygnięcie. Klucz = UUID operacji = RAP WriteRequestId. Blokada wiersza operacji i klucz główny chronią równoległe enqueue. Oczekiwana wersja jest zapisana jako tekst ISO, aby zachować 100 ns niezależnie od precyzji timestampu lokalnej bazy. Nie zastępujemy jej świeżym GET przed ponowieniem. `SapResultWriteHistory` zapisuje QUEUED i wynik rozstrzygnięcia. Nie kopiujemy danych finansowych.

Polecenie, historia i wpis natywnego `SapResultOutbox` powstają atomowo. Worker uruchamia się po commit. `APPLIED`/`ALREADY_APPLIED` oznaczają sukces; konflikty, odmowy, niespójna odpowiedź i nieznany Outcome wymagają rekoncyliacji. No-op może zachować pierwotny request ID SAP różny od bieżącego, zgodnie z kontraktem RAP.

Przejściowa awaria bez Retry-After korzysta z retry/backoff CAP, maksymalnie pięciu nieudanych publikacji. Zawsze ponawiany jest ten sam payload. Jest to ponowienie zastosowania potwierdzonego wyniku w SAP, nie ponowna wysyłka faktury do KSeF. HTTP 429 i jawny Retry-After wstrzymują automatyczne próby; termin jest zachowany w `retryNotBefore`. Kontrolowane wznowienie wymaga odrębnej obsługi operacyjnej, nie dodajemy obecnie publicznego endpointu retry.

Błąd Remote Service może anulować swój CAP ChangeSet. Wywołanie zdalne ma osobny ChangeSet, dzięki czemu worker może trwale zapisać odmowę w swojej transakcji. Zdalny status odczytujemy ze strukturalnych danych błędu CAP 5.2, bez parsowania tekstu. Nie przechowujemy body/cause odpowiedzi w błędach aplikacyjnych. Wyłączono kategorię CAP 5.2 logującą surowe body błędów.

Test restartu wykazał, że w lokalnej konfiguracji single tenant/H2 samo ponowne uruchomienie collectora nie podjęło cichej kolejki. Po `ApplicationReadyEvent`, tylko jeśli istnieją własne PENDING, publikujemy pusty `wakeCollector` przez publiczne API outboxa. Nie edytujemy wewnętrznych rekordów kolejki ani jej liczników. Test potwierdził podjęcie starych wpisów i retry z zachowanym ID/wersją.

Transport ma domyślnie `ainv.sap-write.enabled=false`. Opcjonalny profil `writeback-local` używa plikowej H2; domyślny profil development pozostaje in-memory. Cloud Foundry wymaga trwałej bazy, np. zweryfikowanego bindingu HANA, nie lokalnego dysku.

## Alternatives and Consequences

Nie tworzymy własnego pollera, tabeli lease ani mikroserwisu. Nie używamy pamięciowej kolejki lub `@Async` jako mechanizmu niezawodności. Native outbox zapewnia trwały dispatch, a własny rekord umożliwia niezmienny payload, rozstrzygnięcie i historię.

Po wyczerpaniu retry CAP zachowuje nieobsłużony wpis z licznikiem i bezpiecznym błędem; własne polecenie może pozostać PENDING. Potrzebna jest obsługa operacyjna takich wpisów. Bez SDK i prawdziwego potwierdzenia nie ma jeszcze rzeczywistego producenta ACCEPTED. CAP-04C dostarcza wewnętrzny punkt wejścia i worker, nie pełny proces KSeF. Testy pojedynczej instancji/H2 nie dowodzą działania HANA, multitenancy ani wielu instancji CF. CAP-04D pozostaje osobno zatwierdzanym etapem.

## Aktualizacja 2026-10-29 — CAP-04 Hardening

Zmienia fragmenty decyzji powyżej; szczegóły i dowody w [dzienniku](../journal/2026-10-29-cap-04-hardening.md).

- **Brak HTTP pod blokadą.** Worker nie trzyma `SELECT ... FOR UPDATE` na poleceniu podczas HTTP (potwierdzone sondą na poprzednim kodzie). Przetwarzanie: claim z dzierżawą i tokenem (commit) → HTTP → finalizacja warunkowana tokenem (commit), każde w osobnej transakcji. Wygasła dzierżawa pozwala odzyskać porzucone polecenie; ponowne wywołanie SAP z tym samym `WriteRequestId` jest bezpieczne. Wciąż używamy natywnego outboxa, bez własnego schedulera.
- **Wyczerpanie prób.** CAP po osiągnięciu limitu zostawia wiadomość bez dalszych dostaw, a polecenie `PENDING`. Budżet prób HTTP liczy teraz aplikacja (`RETRY_EXHAUSTED`); limit CAP jest wyższy. Wznowienie tylko przez wewnętrzne API Java (`SapKsefResultWriteRecovery`), z respektowaniem Retry-After, wyłącznie dla przyczyn po stronie SAP i poleceń osieroconych.
- **Integralność.** `enqueue(operationId)` wymaga niezmiennej migawki dokumentu (`KsefInvoiceSnapshots`) i zgodnego skrótu SHA-256 XML potwierdzonego przez KSeF; spółka i wersja pochodzą z migawki. Zastępuje poprzednią sygnaturę `enqueue(operationId, companyCode, expectedVersion)`. Unikalność `(environment, ksefNumber)` tylko dla zapisów do SAP; globalny UNIQUE numeru KSeF jest odrzucony.
- **Granice.** Wszystko poza LIVE jest dowiedzione na H2/mock. Brak producenta migawek, brak interfejsu operacyjnego `resume`, brak dowodu zachowania SAP przy zapisie.
