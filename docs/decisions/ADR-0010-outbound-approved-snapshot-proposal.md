---
title: "ADR-0010 — zatwierdzony snapshot OUTBOUND"
date: 2026-11-15
type: adr
status: proposed
tags: [ainvoicer, outbound, snapshot, rap, cap-java, e2e-01b]
---

# Zatwierdzona wersja do SEND — PROPOSED

[Przewodnik E2E-01B](../guides/E2E_01B_OUTBOUND_SEND_HARDENING.md) · [Dziennik](../journal/2026-11-15-e2e-01b-outbound-hardening.md)

## Kontekst i status

**Nie jest to zaakceptowana decyzja biznesowa ani zgoda na SEND.** E2E-01B wdraża wyłącznie
walidacje i bezwarunkową blokadę pierwszej wysyłki. Nie ma konfiguracji odblokowującej tę bramkę.
ADR-0005 pozostaje obowiązujący; wymaganie NEW jako warunku przygotowania jest jawnym rozszerzeniem
jego punktu 2 (wcześniej integracja nie czytała InvoiceStatus). Integracja nadal nie zmienia tego pola.

Obecne SAP Remote READ składa się z trzech CQN: nagłówek, pozycje, referencje. Nie ma gwarancji
atomowej kompozycji ani zamrożenia danych pomiędzy odczytem i SEND. Header ETag/CAS nie dowodzi
niezmienności dzieci. Odcisk wartości wykrywa obserwowaną różnicę, ale nawet dwa zgodne READ mogą
przeoczyć zmianę i powrót (ABA) lub dać mieszany stan kompozycji.

## Trzy warianty

| Wymiar | A: Freeze SAP | B: Niezmienna wersja SAP | C: Zatwierdzony immutable snapshot CAP |
| --- | --- | --- | --- |
| Gwarancja | Autoryzowany freeze z atomową wersją całej kompozycji blokuje edycję rodzica i dzieci do rozstrzygnięcia | Identyfikator wersji wskazuje stabilne dane rodzica i wszystkich dzieci | Wysyłane są dokładne bajty zaakceptowane przez operatora, powiązane z obserwacją SAP |
| Ryzyko | Długie blokady biznesowe, freeze bez końca po niejednoznacznym SEND | Większa złożoność i ryzyko wskazania niewłaściwej wersji | Zaakceptowana wersja może różnić się od aktualnego SAP; sam snapshot nie dowodzi atomowego READ SAP |
| RAP | Nowa akcja/state, kontrola UPDATE/DELETE wszystkich dzieci, trwała wersja kompozycji | Model wersjonowania/akceptacji nagłówka, pozycji i referencji | Bez freeze; może być potrzebny kontrakt writebacku do wersji/archiwalnego wyniku i atomowy eksport kompozycji |
| CAP | Utrwalenie freeze/version token, recovery i kontrolowane unfreeze | Korelacja do SAP businessVersion, osobne decyzje o duplikacie kolejnych wersji | Niezmienne bajty/metadane, approval, kontrola hasha i wersji approval przed auth, trwały audyt |
| UI5 | Pokazuje stan blokady i procedurę odzyskania | Wybór i prezentacja konkretnej wersji | Preview dokładnego utrwalonego XML i danych, jawne zatwierdzenie snapshotId/SHA/długości/wersji |
| Recovery | Nie zdejmować freeze po UNKNOWN; najpierw reconcile | Wznowić tę samą wersję i operację | Wznowić tę samą operację i approved snapshot, nigdy regenerować XML |
| Writeback | Do zamrożonej wersji, następnie jawna polityka zakończenia freeze | Wynik wiązany z konkretną wersją SAP | Nie wolno przypisać numeru zmienionej fakturze tylko dlatego, że Header CAS przeszedł; wymaga uzgodnienia właściciela |
| Operator | Zgoda na freeze, decyzja recovery/unfreeze | Wybór/zatwierdzenie stabilnej wersji | Świadoma akceptacja wersji archiwalnej; ponowna akceptacja zmian przed pierwszą wysyłką wymaga osobnej polityki |

## Rekomendacja do decyzji

**C** jest najbliższe intencji użytkownika: wiąże zgodę z konkretnym dokumentem, a edycja SAP nie
zmienia zatwierdzonych bajtów. Spełnia wymaganie „właściwej wersji SAP” wyłącznie po przyjęciu
semantyki: właściwa wersja to jawnie obejrzana i zatwierdzona obserwacja SAP utrwalona w CAP.
Nie spełnia wymagania „zawsze aktualny, atomowo odczytany dokument SAP w chwili SEND”. Jeśli to
drugie jest wymagane, potrzebne A lub B, ewentualnie atomowy eksport wersji SAP połączony z C.

Samo skopiowanie bajtów i postawienie flagi `approved=true` bez związania jej z kompletnym
dokumentem, principalem i wersją polityki jest niewystarczające. Approval musi mieć autoryzację
KsefOperator oraz zaufany zakres NIP/spółka; UI nie dostarcza danych sprzedawcy ani gotowego XML.

## Minimalna propozycja modelu — NIE ZAIMPLEMENTOWANO

Nie zmieniamy CDS/HANA, indeksów ani istniejących fingerprintów w tym sprincie.

1. Niezmienna obserwacja snapshotu: `compositionFingerprintVersion`, `compositionSha256`,
   `partyDataSha256`, `generatedAt`, jednoznaczne mapowanie do dokładnych bajtów FA(3).
   Znormalizowane metadane/preview kompozycji i PartyData w zarządzanym storage, z referencją/hash
   w DB. Nie dopisywać wrażliwych danych do logów ani repozytorium.
2. Osobny INSERT-only approval z `operationId`, `snapshotId`, `xmlSha256`, `xmlByteLength`,
   `compositionSha256`, `partyDataSha256`, `environment`, `taxpayerNip`, `companyCode`,
   `approvedBy`, `approvedAt`, `policyVersion`; unikalność logicznej zgody i trwały audyt.
3. Atomowe powiązanie rezerwacji/operacji z approval; worker wymaga dokładnie tego związania.
   Model odwołania zgody/wyścigu z claim oraz granica po pierwszym efekcie wymagają osobnego projektu.
   Starsze rekordy bez tych dowodów pozostają zablokowane; bez automatycznego backfillu zgody.

Akceptacja ADR nie odblokowuje aktualnego kodu. Potrzebna osobno zlecona implementacja schema,
approval, UI i testów. Zachować działające wersje legacy; żadnej destrukcyjnej migracji.

## Writeback i decyzje właściciela

Obecne `ApplyKsefResult` używa wersji nagłówka. Nie zmieniamy ExpectedVersion, nie obchodzimy CAS
i nie nadpisujemy zmienionego SAP. Konflikt pozostaje do ręcznego uzgodnienia, a ACCEPTED/UPO
w CAP są zachowane. Dla C trzeba zatwierdzić, czy wynik dotyczy wyłącznie archiwalnej wersji,
czy też można go pokazać na bieżącym nagłówku SAP i pod jakimi warunkami.

Właściciel wybiera gwarancję (aktualny SAP albo zatwierdzona wersja), semantykę writebacku,
zakres odpowiedzialności operatora oraz potrzebę zmian RAP. Dopiero potem można opracować
implementację approval/CDS i procedurę pierwszego, osobno autoryzowanego SEND.
