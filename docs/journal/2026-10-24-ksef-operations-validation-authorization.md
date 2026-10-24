---
title: "KsefOperations — walidacja i autoryzacja podatnika"
date: 2026-10-24
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, ksef, security, odata-v4]
---

# KsefOperations — walidacja i autoryzacja podatnika

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Poprzedni krok: KsefService](2026-10-24-ksef-service-definition.md)

## Date

2026-10-24 (Europe/Warsaw).

## Branch

`java-cap-ksef`; drzewo było czyste przed rozpoczęciem.

## Goal

Walidacja CREATE i autoryzacja per NIP dla istniejącego `KsefService`. Bez KsefInvoices, SDK KSeF, RAP Remote Service, IAS/XSUAA, commita i deployu.

## Initial State

- Repozytorium zawierało model `KsefOperations`, projekcję `KsefService` z `@requires: 'authenticated-user'`, Spring Security i test `SELECT 1` na H2.
- CAP Java `5.2.0`, Spring Boot `4.1.1`, JDK `25.0.4.1`, Maven `3.9.16`; w projekcie są też CDS DK `10.2.0`, CDS `10.2.1` i compiler `7.2.1`.
- Sprawdzono oficjalną dokumentację CAP dotyczącą `@restrict`, user attributes, mock users, błędów oraz event handlers. CAP Java sprawdza instancyjne warunki dla danych CREATE/UPDATE; lista `$user.<attribute>` oznacza dopasowanie jednej z jej wartości. `READ` dodaje warunek do zapytania.

## Implementation

- `KsefService.KsefOperations` przyznaje tylko `CREATE` i `READ` roli `KsefOperator`, z warunkiem `taxpayerNip = $user.taxpayerNips`. Usługa nadal wymaga `authenticated-user`; nie przyznano UPDATE ani DELETE.
- Dodano minimalny Java `@Before CREATE`. Sprawdza NIP (10 cyfr i checksum), kierunek `INBOUND`/`OUTBOUND`, katalog `SEND_INVOICE`, `CHECK_STATUS`, `RECEIVE_INVOICE`, wymagany klucz idempotencji (trim) oraz wymusza status `NEW`. Odrzucenia są `ServiceException(BAD_REQUEST)`. CAP persistence obsługuje CRUD.
- Mock users wyłącznie w testowym `application-test.yaml`: `operator-a` ma rolę `KsefOperator` i NIP `1234567802`; `operator-b` ma tę rolę i NIP `9876543210`; oddzielny operator ma rolę bez atrybutu `taxpayerNips`; jest też użytkownik z `authenticated-user` bez `KsefOperator`. W testach są jawne syntetyczne hasła, nie są to credentiale produkcyjne.
- Testy startują prawdziwy Spring Boot na losowym porcie i wysyłają HTTP do OData V4; używają Spring Security, CAP Runtime, H2 i mock users z profilu `test`.

## Changed Files

- `cap-java/srv/ksef-service.cds` — instancyjne reguły `@restrict`.
- `cap-java/srv/src/main/java/customer/cap_java/KsefOperationsHandler.java` — walidacja i inicjalizacja statusu.
- `cap-java/srv/src/test/java/customer/cap_java/KsefServiceODataIntegrationTest.java` — 12 scenariuszy HTTP/OData.
- `cap-java/srv/src/test/resources/application-test.yaml` — testowa konfiguracja H2 oraz mock users.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` i ten wpis — bieżący stan, plan, indeks i dowody.

## Commands Executed

W katalogu `D:\AInvoicer\cap-java`:

- `java -version`, `mvn -version`, `node --version`, `npm --version` — wersje zgodne z wartościami powyżej.
- `mvn clean verify` — FAIL podczas `cds version`: sandbox zwrócił `spawn EPERM`.
- `mvn test '-Dcds.cds.skipVersionCheck=true' '-Dtest=KsefServiceODataIntegrationTest'` — PASS po iteracyjnych korektach; końcowe pełne sprawdzenie poniżej.
- `mvn clean verify '-Dcds.cds.skipVersionCheck=true'` — BUILD SUCCESS; `H2DataSourceIntegrationTest` 1/1, OData `KsefServiceODataIntegrationTest` 14/14; razem 15/15.
- Uruchomiono `java -jar srv/target/cap-java-exec.jar --spring.profiles.active=test --spring.config.additional-location=file:./srv/target/test-classes/ --server.port=8081`; testowy plik konfiguracyjny pozostał w `target/test-classes`. Ręczny HTTP POST/GET OData odpowiedział 201/200; aplikację zatrzymano po sprawdzeniu.

Flaga pomija kontrolę wersji CDS uruchamiającą proces potomny. CDS model, generowanie kodu, kompilacja, H2 i testy przeszły; nie jest to naprawa błędu środowiska Node `spawn EPERM`.

## Tests & Results

| Scenariusz | Wynik lokalny |
| --- | --- |
| Operator A CREATE własnego NIP i READ swoich operacji | PASS, HTTP 201/200 |
| Operator A CREATE NIP B | PASS odmowy, HTTP 400 (warunek instancji CREATE) |
| Operator A nie widzi operacji B | PASS; lista OData nie zawiera NIP B |
| Anonimowy użytkownik | PASS odmowy, HTTP 401 |
| Użytkownik bez KsefOperator | PASS odmowy, HTTP 403 |
| Operator bez `taxpayerNips` | PASS: CREATE odrzucony HTTP 400; READ zwraca pusty zakres |
| NIP z błędną sumą kontrolną | PASS odmowy, HTTP 400 |
| Nieobsługiwany operationType | PASS odmowy, HTTP 400 |
| Pusty klucz idempotencji i nieprawidłowy direction | PASS odmowy, HTTP 400 |
| Status przesłany jako ACCEPTED | PASS; zapisany status to NEW, a idempotencyKey jest przycięty |
| PATCH/DELETE publicznego endpointu | PASS odmowy, HTTP 403/403 |
| Duplikat pary NIP + idempotencyKey | PASS odmowy, HTTP 409 z trwałego ograniczenia unikalności |
| Test H2 DataSource | PASS, `SELECT 1` |

To są lokalne testy z użytkownikami mock. Nie łączono się z IAS/XSUAA, SAP ani KSeF.

## Access Model and Limitations

- `@requires` sprawdza statyczną rolę/uwierzytelnienie dla serwisu lub zasobu; nie filtruje instancji po NIP.
- `@restrict` deklaruje uprawnione zdarzenia, role i warunek instancji; CAP dla READ wprowadza filtr, a dla CREATE sprawdza payload wejściowy. Atrybutu NIP nie bierze z JSON jako dowodu uprawnienia.
- `@Before` uruchamia Java kod przed domyślnym handlerem CAP. Tu waliduje i normalizuje wejście; wyjątek kończy żądanie i transakcję. Nie zastępuje mechanizmu autoryzacji CDS.
- Analogicznie RAP rozdziela `authorization` w behavior definition/implementation od `validation` wykonywanej dla zmiany encji. Pojęcia są podobne, ale API, kolejność i transakcje CAP oraz RAP nie są tożsame.
- Testowy profil nie dowodzi konfiguracji produkcyjnego IAS/XSUAA. Przed udostępnieniem API trzeba skonfigurować zaufane mapowanie roli `KsefOperator` i wielowartościowego claimu `taxpayerNips`, sprawdzić zachowanie dla braku/pustej listy oraz chronić zmianę uprawnień administracyjnie.

## Current State

Walidacja, autoryzacja CREATE/READ per NIP, blokada UPDATE/DELETE oraz testy integracyjne są ukończone dla CAP Java/H2 lokalnie. Zwykłe `mvn clean verify` nadal ma problem z `spawn EPERM`; wariant z jawnie dozwolonym obejściem przechodzi.

## Next Steps

1. Zaprojektować encję `KsefInvoices` i oddzielenie statusu technicznego od biznesowego.
2. Przed użytkowaniem poza testami skonfigurować i zweryfikować IAS/XSUAA oraz mapowanie zaufanych claimów podatnika.
