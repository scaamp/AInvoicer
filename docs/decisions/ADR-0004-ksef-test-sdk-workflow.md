---
title: "ADR-0004 — SDK MF i trwałe etapy wysyłki KSeF TEST"
date: 2026-10-29
type: adr
status: zaakceptowany
tags: [ainvoicer, cap-java, ksef, outbox]
---

# SDK MF i trwałe etapy wysyłki TEST

## Context

Agent A integruje SDK/auth/sesje, Agent B niezależnie przygotowuje FA(3).
Zlecenie użytkownika obejmuje jedną nową syntetyczną fakturę w TEST i commit/push
wyłącznie `feature/ksef-auth`. Nie obejmuje HTTP WRITE H01 ani deployu.

## Decision

SDK `pl.akmf.ksef-sdk:ksef-client:3.0.29` działa w tej samej JVM co CAP 5.2 / Boot 4.1.1.
Przypięty tag `v3.0.29`, commit `3c3a899541074aa4bd944636b25659aef7d23da7`.
Budowanie oficjalnego modułu Gradle do lokalnego repo Maven pozwala pracować bez PAT.
Osobny Jackson 2 obsługuje DTO SDK; mapper Spring pozostaje własnością Boot.

Istniejące `KsefOperations`, `KsefInvoices` i `KsefInvoiceSnapshots` zachowują role.
Nowe wewnętrzne `KsefSubmissions` i historia opisują dostarczenie przez natywny
`KsefSubmissionOutbox`: claim → commit etapu → HTTP → commit odpowiedzi. Transakcja
collectora może pozostawać otwarta, lecz nie trzyma blokad wierszy biznesowych.
UUID zlecenia jest UUID operacji; unikalny snapshot blokuje wysłanie tych samych
bajtów przez inne zlecenie. Endpointy publiczne nie wystawiają zleceń ani tokenów.

Nie utrwalamy AES/IV/JWT. Restart w etapie efektu oznacza
`RECONCILIATION_REQUIRED`. Odczyt istniejącej sesji po hashu może odnaleźć utraconą
odpowiedź send; brak dopasowania nie daje prawa do ponownej wysyłki. Niepewny close
sprawdzamy odczytem statusu sesji. Niepewnego redeem nie powtarzamy. Potwierdzony
`ACCEPTED` wymaga statusu faktury 200, numeru, referencji i zgodnego hash SHA-256.
Opóźnione UPO nie cofa przyjęcia. Limit i harmonogram odczytów są trwałe, z jitter
i respektowaniem Retry-After. Wyczerpanie wymaga działań operatora backendu.

`KsefDocumentStore` oddziela storage od procesu. Lokalny eksperyment TEST może
jawnie skonfigurować katalog poza Git: nazwa dokumentu to hash bajtów, zapis jest
atomowy. Adapter odmawia konfiguracji na CF (`VCAP_APPLICATION`). Jest to świadomy
magazyn lokalnego testu, nie rozwiązanie produkcyjne. Przed CF potrzebny jest adapter
zarządzanego repozytorium dokumentów oraz trwała baza. XML/UPO nie trafiają do DB.

Transport i XAdES TEST są domyślnie wyłączone. NIP z konfiguracji wiąże credential
z kontekstem podatnika. Wywołanie wewnętrznego enqueue wymaga wcześniej autoryzowanej
logiki aplikacyjnej, nie jest nowym publicznym API ani samodzielnym nadaniem uprawnień.

## Consequences

Proces jest konserwatywny: awaria po otwarciu sesji przed send może wymagać ręcznego
uzgodnienia, ponieważ materiał szyfrowania nie jest utrwalany. To koszt uniknięcia
persistencji sekretów i automatycznego duplikowania faktur. H2/single tenant testuje
lokalną niezawodność; HANA, wiele instancji CF i zarządzany storage pozostają poza
potwierdzonym zakresem. Nie uruchamiamy automatycznie writeback do SAP.

## Uzupełnienie — integracja B

Commit `7075c01` włączono jako `657c285`, bez zmian kodu/zasobów B. Producent
`KsefInvoicePreparation` łączy SAP snapshot i PartyData z generatorem oraz outboxem.
`KsefFa3Documents` przywraca strumieniowo 12 dużych maxOccurs rozluźnionych przez
walidator B z powodu ograniczeń DFA JDK. Nie traktujemy samej rozluźnionej walidacji
za pełną kontrolę liczności. Testy sprawdzają wszystkie limity wobec oryginalnego
XSD; wygenerowany XML potwierdzono również pełnym XSD/lxml bez zmian limitów.

Źródła: [SDK MF](https://github.com/CIRFMF/ksef-client-java/tree/v3.0.29),
[auth MF](https://github.com/CIRFMF/ksef-api/blob/main/uwierzytelnianie.md),
[CAP Event Queues](https://cap.cloud.sap/docs/java/event-queues).
