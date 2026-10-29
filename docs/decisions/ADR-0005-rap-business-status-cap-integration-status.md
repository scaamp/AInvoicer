---
title: "ADR-0005 — RAP jako właściciel statusu biznesowego, CAP jako właściciel statusu integracji"
date: 2026-10-29
type: adr
status: PROPOSED
tags: [ainvoicer, architektura, rap, cap-java, sapui5, ksef, status, eventual-consistency]
---

# ADR-0005 — RAP Business Status / CAP Integration Status

**Status: PROPOSED** — projekt do zatwierdzenia. Nie zmienia jeszcze ADR-0001…0004 ani kodu.

[Audyt 2026-10-29](../audits/2026-10-29-rap-cap-status-ownership.md) · [ADR-0001](ADR-0001-cap-java-ksef-architecture.md) · [ADR-0002](ADR-0002-sap-ksef-result-write-contract.md) · [ADR-0003](ADR-0003-cap-sap-result-writeback.md)

## Context

- ADR-0001 opisuje SAPUI5 → CAP → SDK, a przewodnik kokpitu planuje `KsefCockpitService.Invoices` — proxy danych SAP w CAP.
- RAP ma już cztery readonly pola wyniku KSeF i akcję `ApplyKsefResult` z idempotencją (ADR-0002); CAP ma writeback przez persistent outbox (ADR-0003).
- Na `feature/ksef-auth` CAP potwierdza przyjęcie przez KSeF (`KsefSubmissions`, `KsefOperations`), ale **nie zleca writebacku** (ADR-0004).
- SAPUI5 wylicza status z priorytetem najnowszej operacji CAP nad numerem w RAP.
- W CAP ta sama nazwa (`ACCEPTED`, `REJECTED`, `RECONCILIATION_REQUIRED`) występuje w kilku maszynach stanów o różnym znaczeniu.

Potrzebny jest jednoznaczny podział: kto jest źródłem prawdy dla którego stanu, skąd UI czyta dane i jak dwa systemy dochodzą do spójności bez transakcji rozproszonej.

## Decision

1. **SAP RAP** jest właścicielem danych faktury i **biznesowego wyniku KSeF**: `KsefNumber`, `KsefEnvironment`, `KsefRecordedAt`, `KsefWriteRequestId`. Wypełnione pola = „KSeF przyjął fakturę w tym środowisku i SAP to zapisał”.
2. **`InvoiceStatus`** pozostaje statusem dokumentu SAP. Integracja KSeF go nie czyta ani nie zmienia. Jego semantykę zdefiniuje osobna decyzja.
3. **Nie dodajemy `KsefBusinessStatus`** do RAP dla OUTBOUND. Odrzucenie przez KSeF jest wynikiem próby, nie faktem prawnym o dokumencie; pozostaje w CAP.
4. **CAP Java** jest właścicielem **wykonania integracji** (`KsefSubmissions`), **publicznego podsumowania operacji** (`KsefOperations`) i **synchronizacji do SAP** (`SapResultWrites`). CAP nie przechowuje danych finansowych faktury.
5. **SAPUI5** czyta listę, nagłówki, pozycje, referencje i wynik biznesowy **bezpośrednio z RAP OData V4** (named model). Z CAP korzysta wyłącznie do akcji (`requestSend`, operacje administracyjne) i odczytu stanu integracji per faktura. **Nie tworzymy proxy danych SAP w CAP.**
6. **Writeback następuje raz, po potwierdzonym `ACCEPTED`**, niezależnie od UPO. Zlecenie powstaje w tej samej transakcji, która utrwala przyjęcie; wykonanie po commit przez outbox.
7. **Blokada duplikatów wysyłki jest serwerowa w CAP.** UI tylko ją odzwierciedla.

## Responsibilities

| Komponent | Odpowiada za | Nie odpowiada za |
| --- | --- | --- |
| RAP | dane faktury, status dokumentu SAP, zapis potwierdzonego wyniku KSeF, autoryzacja odczytu/zapisu w SAP | protokół KSeF, retry, historia prób |
| CAP | auth KSeF, FA(3), wysyłka, polling, UPO, rekoncyliacja, historia, writeback, idempotencja wysyłki | dane finansowe, status dokumentu SAP, lista faktur dla UI |
| SAPUI5 | prezentacja, łączenie RAP + stan CAP po `InvoiceUUID`, wywołanie akcji | wyliczanie statusu biznesowego z danych technicznych, ochrona przed duplikatem |

## Data Ownership

| Dane | Źródło prawdy | Kopia | Uzasadnienie kopii |
| --- | --- | --- | --- |
| Nagłówek, pozycje, referencje | RAP | migawka metadanych w CAP (`KsefInvoiceSnapshots`: wersja, hash, odcisk pozycji) | dowód, co wysłano |
| Numer KSeF faktury sprzedażowej | RAP (po writebacku) | `KsefOperations.ksefNumber`, `KsefSubmissions.ksefNumber`, `SapResultWrites.ksefNumber` | dowód procesu i payload writebacku |
| Wynik próby (przyjęta/odrzucona/nieznana) | CAP `KsefOperations` | — | — |
| Etap techniczny, sesja, UPO | CAP `KsefSubmissions` + magazyn dokumentów | — | — |
| Stan synchronizacji z SAP | CAP `SapResultWrites` | — | — |
| `KsefInvoices.businessStatus` | **brak roli dla OUTBOUND** | — | zarezerwowane dla INBOUND (decyzja otwarta) |

## State Machines

Trzy niezależne maszyny stanów; brak wspólnego enum.

**A. RAP (biznes):** `brak wyniku` → `wynik zapisany` (przez `APPLIED`). Brak przejścia powrotnego.

**B. CAP integracja (`KsefSubmissions.state`):**
`PREPARED → AUTHENTICATING → OPENING → OPENED → SENDING → POLLING → {ACCEPTED | INVOICE_REJECTED} → CLOSING → {WAITING_UPO → COMPLETED | REJECTED}`; stany zatrzymania: `RECONCILIATION_REQUIRED`, `READ_RETRY_EXHAUSTED`.

`KsefOperations.status` jest podsumowaniem dla użytkownika: `NEW → QUEUED → SUBMITTED → {ACCEPTED | REJECTED | RECONCILIATION_REQUIRED}`; `ACCEPTED`/`REJECTED` nie są nadpisywane. **Status biznesowy próby czytamy z `KsefOperations`, techniczny z `KsefSubmissions`. `COMPLETED` oznacza koniec procesu z UPO, nie moment przyjęcia.**

**C. CAP writeback (`SapResultWrites.state`):** `PENDING ⇄ PROCESSING → {SUCCEEDED | RETRY_EXHAUSTED | RECONCILIATION_REQUIRED}`; `resume` operatora: `RETRY_EXHAUSTED | RECONCILIATION_REQUIRED(dostępność SAP) | osierocone → PENDING`. Stan writebacku nigdy nie zmienia stanów A ani B.

Prezentacja w UI (kolejność rozstrzygania):

1. RAP ma numer → „Przyjęta w KSeF” (stan CAP tylko jako informacja dodatkowa).
2. Operacja `ACCEPTED`, RAP bez numeru → „Przyjęta w KSeF — synchronizacja z SAP” / „konflikt zapisu w SAP”.
3. Operacja w toku → „Wysyłanie”; `RECONCILIATION_REQUIRED` → „Wymaga uzgodnienia”; `REJECTED` → „Odrzucona”.
4. Brak operacji i numeru → „Nie wysłano”.

## Eventual Consistency

„KSeF przyjął fakturę” i „SAP zapisał wynik” to dwa niezależne fakty; nie łączymy ich w transakcję rozproszoną.

1. Przyjęcie i zlecenie writebacku zatwierdzamy razem w bazie CAP. Błąd walidacji zlecenia **nie może** wycofać przyjęcia.
2. Outbox dostarcza zlecenie po commit; payload i `WriteRequestId` (= UUID operacji) są niezmienne.
3. Sweeper przy starcie i okresowo zleca writeback dla operacji `ACCEPTED` bez `SapResultWrites` (idempotentnie).
4. `ApplyKsefResult` rozstrzyga ponowienia (`ALREADY_APPLIED`) i chroni przed nadpisaniem (`RESULT_CONFLICT`).
5. Po `SUCCEEDED` jedynym źródłem dla UI jest RAP.

## Failure Recovery

| Awaria | Zachowanie |
| --- | --- |
| SAP niedostępny / 5xx / timeout | retry tego samego payloadu (max 5), potem `RETRY_EXHAUSTED`; `resume` operatora |
| 429 / Retry-After / odmowa HTTP / transport wyłączony | `RECONCILIATION_REQUIRED` (dostępność), `resume` po terminie |
| `VERSION_CONFLICT` | wymaga polityki (Open Questions Q3); nigdy automatycznie |
| `RESULT_CONFLICT`, `IDEMPOTENCY_CONFLICT`, niespójna odpowiedź | rekoncyliacja ręczna; bez automatycznego retry |
| Restart CAP | checkpointy submission + collector; sweeper writebacku |
| Utrata odpowiedzi KSeF na send/close | `RECONCILIATION_REQUIRED`; tylko odczyty (`reconcile`) |
| Duplikat zgłoszony przez KSeF | `RECONCILIATION_REQUIRED`, nie `REJECTED` |

Żadna ścieżka odzyskiwania writebacku nie wywołuje KSeF.

## UI Data Sources

| Dane | Usługa | Model UI5 |
| --- | --- | --- |
| Lista, filtrowanie, sortowanie, paginacja, szczegóły, pozycje, referencje, `InvoiceStatus`, wynik KSeF | RAP OData V4 **UI** (read-only, nowa usługa obok Web API) | named model `rap`, `operationMode: Server` |
| Stan integracji dla widocznych faktur | CAP `KsefService` (odczyt per `sapDocumentRef`, bez danych SAP) | model pomocniczy |
| `requestSend`, operacje administracyjne | CAP akcje OData V4 | — |
| XML/UPO (później) | CAP | — |
| SAP FI Legacy, Scanner, AI (Python/Node) | bez zmian | bez zmian |

Filtrowanie po stanie integracji wykonujemy dwufazowo (CAP → lista UUID → RAP `InvoiceUUID in (…)`), z limitem.

## Security Considerations

- Trzy kanały tożsamości: business user → RAP (usługa UI, DCL, IAM), business user → CAP (XSUAA/IAS, `KsefOperator`, `$user.taxpayerNips`), CAP → RAP (communication user, wyłącznie ACTVT 16 dla `ApplyKsefResult`).
- Zakres NIP/CompanyCode w DCL RAP i w atrybutach CAP musi być ten sam; obecny DCL `where TRUE` nie spełnia tego warunku.
- Usługa RAP UI jest tylko do odczytu i **nie** wystawia `ApplyKsefResult`.
- `requestSend` nie ufa NIP ani danym z przeglądarki; czyta RAP przez communication usera i sprawdza zakres użytkownika.
- JWT KSeF i materiał kryptograficzny nie opuszczają CAP; UI nie łączy się z KSeF.

## Consequences

- Brak proxy danych SAP w CAP: mniej kodu i brak replikacji, ale UI zależy od dwóch usług i dwóch modeli autoryzacji.
- Potrzebna praca ABAP (usługa UI, DCL odczytu, IAM) przed przełączeniem kokpitu z mock.
- Krótkie okno niespójności (przyjęta w KSeF, brak w SAP) jest widoczne i obsługiwane w UI przez stan CAP.
- `KsefInvoices.businessStatus` traci rolę dla OUTBOUND.
- ADR-0004 („bez automatycznego writebacku”) zostanie zastąpiony w tym punkcie; przewodnik kokpitu (G-01) i ROADMAP Phase 5 wymagają korekty.

## Alternatives Rejected

| Alternatywa | Powód odrzucenia |
| --- | --- |
| CAP jako jedyne API dla UI (proxy RAP w CAP, dawne G-01) | Replikacja kontraktu SAP, dodatkowa warstwa i synchronizacja. Pozostaje **awaryjnym fallbackiem** do decyzji, jeśli usługi RAP UI nie da się udostępnić w Trial |
| `KsefBusinessStatus` w RAP | Duplikuje informację wynikającą z czterech pól; odrzucenie nie jest faktem o dokumencie |
| Writeback dopiero po `COMPLETED` (UPO) | Opóźnia zapis faktu prawnego i uzależnia go od dostępności UPO |
| Dwa etapy writebacku (ACCEPTED i UPO) | Brak biznesowej potrzeby UPO w RAP; podwaja ścieżki konfliktów |
| Ochrona przed duplikatem tylko w UI | Nie chroni przed wieloma kartami, użytkownikami ani wywołaniami API |
| Transakcja rozproszona CAP+RAP | Niewspierana i sprzeczna z asynchronicznym modelem KSeF |

## Open Questions

1. Polityka `VERSION_CONFLICT` po przyjęciu: blokada zmian treści w RAP po zleceniu wysyłki czy kontrolowane ponowne zastosowanie po kontroli dryfu?
2. Czy faktura z numerem KSeF ma być nieusuwalna i nieedytowalna w RAP (precheck)?
3. Semantyka i wartości `InvoiceStatus`.
4. Zakres DCL odczytu i sposób nadania ról business userom w H01 Trial.
5. Źródło `PartyData` (adresy, JST/GV) dla generatora FA(3).
6. Model INBOUND: jak zapisać numer KSeF faktury odebranej (rozszerzenie akcji czy tworzenie) i czy `KsefInvoices` zostaje tylko dla INBOUND.
7. Rola administratora dla `resume`/`reconcile` i forma interfejsu operacyjnego.
8. Kod statusu KSeF dla duplikatu w aktualnym kontrakcie API 2.0 — do potwierdzenia przed implementacją klasyfikacji.
