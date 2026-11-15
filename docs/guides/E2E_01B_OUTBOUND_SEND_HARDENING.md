---
title: "E2E-01B — hardening produkcyjnego OUTBOUND"
date: 2026-11-15
type: guide
status: offline-validation-send-blocked
tags: [ainvoicer, e2e-01b, outbound, rap, snapshot, security, recovery]
---

# E2E-01B — produkcyjne przygotowanie i bramka SEND

[Dziennik / wyniki testów](../journal/2026-11-15-e2e-01b-outbound-hardening.md) ·
[ADR-0010 PROPOSED](../decisions/ADR-0010-outbound-approved-snapshot-proposal.md) ·
[E2E-01A](E2E_01A_SAP_FA3_PREFLIGHT.md) · [FA(3)](FA3_XML_GENERATION.md)

**SEND NO-GO.** Bez zaakceptowanej i zaimplementowanej polityki spójności worker zatrzymuje każdą
nową wysyłkę przed auth/open/send, również dla UNVERIFIED fixture. Samo `ainv.ksef.enabled=true`
nie wystarcza. Nie dodano bypassu ani endpointu XML. Istniejące operacje po SEND mogą dalej
korzystać z bezpiecznych odczytów/recovery; niniejsza sesja ich nie uruchamiała LIVE.

## Audyt kodu i transakcji

| Etap | Stan zastany / ustalenie | Zmiana E2E-01B |
| --- | --- | --- |
| requestSend | Jedynym publicznym argumentem UUID; role i trusted scope z CAP; OFF odmawia przed READ | Zachowane, UUID wyłącznie kanoniczny i niezerowy |
| RAP READ | Adapter: nagłówek + dzieci w trzech RemoteService CQN, communication user, poza lokalną atomowością SAP | Dodatkowy pełny READ po walidacji/generowaniu i przed pierwszym INSERT |
| Kontekst | NIP i spółka z RAP vs atrybuty operatora, TEST i konfigurowany NIP | Zachowane; drugie porównanie obejmuje także zmiany kontekstu |
| PartyData | Backend per NIP, brak domyślnych adresów i flag, zegar backendu | Porównanie wartości konfiguracji między odczytami; createdAt nie jest wersją konfiguracji |
| XML | Generowany w żądaniu przed rezerwacją, mapper → domena → writer → XSD/limity | Wspólne dodatkowe kontrole SAP przed generowaniem, bez korekt źródła |
| Rezerwacja | PK `(InvoiceUUID, TEST)`, historia blokująca legacy/ACCEPTED/UNKNOWN; kontrolowany retry REJECTED domyślnie OFF | Zachowana, nie zwalniamy jej po odmowie workera |
| Zapis | Rezerwacja, operacja, snapshot, submission, historia i persistent outbox w jednym ChangeSet żądania | Nie zmieniono granic transakcji |
| Storage | `documents.put` przed outbox commit; storage może commitować niezależnie | Nadal możliwy osierocony, adresowany hashem dokument po rollback; brak HTTP i brak aktywnego outboxa |
| Worker | Osobny claim/lease i checkpointy przed HTTP; hash XML + XSD; brak kontroli wersji całej kompozycji | PREPARED wymaga zgodnych snapshot/op/rezerwacji/hash/długości, potem bezwarunkowo odmawia z braku approval |
| Auth/open/send | AUTHENTICATING/OPENING/SENDING commitowane przed kolejnymi efektami; restart etapu efektu → reconciliation | Nowa bramka wykonuje się przed AUTHENTICATING i pierwszym wywołaniem auth |
| Polling/ACCEPTED | Bezpieczne READ z backoff/jitter/Retry-After; dowód hash/ref/numer; osobny commit ACCEPTED | Zachowane; nowa blokada nie odcina istniejącego POLLING ani WAITING_UPO |
| SAP writeback | ACCEPTED commit przed osobnym enqueue; sweeper tylko SAP_RAP; stały WriteRequestId i CAS nagłówka | Bez zmian; CAS nadal nie dowodzi zgodności dzieci |
| Close/UPO | Niezależne od writebacku; nieznany close nie jest powtarzany; UPO może być niedostępne | Zachowane; brak UPO lub błąd SAP nie powodują nowego SEND |

Luki statusu/pozycji i brak gwarancji kompozycji były już w `main=744836c`, przed E2E-01A.
Testowy harness ich nie wprowadził. Nową, zamierzoną zmianą zachowania jest bezwarunkowa
blokada pierwszego efektu workera; dotychczasowe standalone eksperymenty SEND również są zablokowane.

## Walidacja przed pierwszym zapisem

`KsefOutboundInvoiceValidator` jest używany przez produkcyjny handler i testowy preflight.
Łącznie z istniejącym mapperem, domeną i XSD wymaga:

- zgodnego kanonicznego InvoiceUUID, NEW/OUTBOUND/STANDARD/PLN i pustych wszystkich pól wyniku SAP;
- zaufanego NIP sprzedawcy/spółki; sum kontrolnych NIP sprzedawcy i nabywcy, nazw, daty, numeru, adresu
  sprzedawcy i jawnych JST/GV, bez danych sprzedawcy z UI;
- wersji nagłówka i każdej pozycji, rodzica każdego dziecka, jednoznacznych ItemUUID i dodatnich ItemNo
  1..999999 (porównanie numeryczne, więc `10` i `000010` są duplikatem), niezerowych UUID;
- jednostek EA/PC, par VAT23/23, VAT8/8, VAT5/5, PLN każdej pozycji;
- dodatniej ilości, nieujemnej ceny i kwot; skali ilości ≤6, ceny ≤8, kwot i stawki ≤2;
- `net = HALF_UP(quantity × price, 2)`, `tax = HALF_UP(net × rate / 100, 2)`, `gross = net + tax`;
- zgodności wszystkich sum pozycji z nagłówkiem oraz istniejącej reguły VAT od sumy stawki w domenie FA(3).

To ostrożna, jawna polityka wspieranego wariantu. Rozbieżność podatku liczonego per pozycja i per stawka
jest odmową, nawet gdy wynika z innej poprawnej biznesowo polityki zaokrągleń. Kod niczego nie poprawia.
Referencje są kontrolowane pod kątem rodzica i duplikatu UUID; obecny generator nie umieszcza ich w XML.

Błędne dane kończą się 422 (kody bez wartości finansowych), drift obserwacji 409, błąd READ 502.
Nie powstają rezerwacja, snapshot, operacja, submission, outbox ani zapis storage. Role/transport OFF
zachowują wcześniejsze kody i kolejność odmowy. Istniejący SEND blokuje kolejną operację w `reserve`.

## Model wersji kompozycji i granice dowodu

`sapHeaderVersion` to timestamp nagłówka. Dotychczasowy `itemsFingerprint` to hash UUID + timestampów
pozycji; wykrywa dodanie/usunięcie i zmianę wersji, ale nie zmianę wartości bez zmiany timestampu.
`detectDrift` nadal ma tę ograniczoną semantykę. Nie zmieniono algorytmu istniejących zapisów.

Nowy `KsefCompositionEvidence` oblicza wyłącznie w pamięci wersjonowaną obserwację `SAP-OBSERVATION-v1`:
wszystkie odczytane pola nagłówka, pozycji i referencji oraz adresy i JST/GV PartyData. Klucze i wiersze
są sortowane, granice wartości kodowane długością, null odrębny od tekstu, BigDecimal normalizowany
bez zaokrąglania. Odcisk nie jest nowym polem DB ani trwałym dowodem approval. Zmiana createdAt przy
drugim wywołaniu provider nie jest driftem konfiguracji, ale dokładne bajty XML są związane ze swoim
oryginalnym czasem generowania i SHA. Nowy preflight nie zatwierdza XML przyszłego SEND.

| Zmiana | Wykrywana podczas przygotowania | Po commit snapshotu / outboxa |
| --- | --- | --- |
| Dodanie/usunięcie pozycji, zmiana pola bez timestampu | Tak, pełny odcisk READ | Każdy nowy SEND zablokowany, bez regeneracji |
| Nagłówek, status, KSeF result | Tak | Jak wyżej |
| Referencje | Tak, konserwatywnie wszystkie odczytane pola | Jak wyżej |
| Adres/flag PartyData | Tak, ponowny odczyt zaufanego providera | Jak wyżej |
| Edycja po ostatnim READ lub ABA | Brak gwarancji wykrycia | SEND nie jest dopuszczony; wymaga polityki ADR-0010 |

Drugi READ nie jest blokadą atomową, a outbox/lease CAP nie blokuje edycji SAP. Nie zapisujemy
fikcyjnego „zatwierdzono” na podstawie dwóch zgodnych READ.
Kompletność dzieci przy limitach/stronicowaniu faktycznej usługi RAP nie została zweryfikowana
LIVE: adapter nie utrwala dowodu liczności/nextLink. Sumy ograniczają część ryzyka obcięcia pozycji,
ale nie dowodzą kompletności referencji ani atomowości. To dodatkowa bramka przed approval/SEND.

## Bramka workera i recovery

`KsefInitialSendGuard` sprawdza zapisane powiązania, SAP_RAP, TEST, NIP, spółkę, OUTBOUND/SEND_INVOICE,
stan QUEUED, wersję, schemat FA(3), SHA i długość XML, brak wcześniejszych referencji efektów,
rezerwację tej operacji i deterministyczny klucz próby oraz brak konkurencyjnego nierozstrzygniętego SEND.
Nawet poprawny komplet kończy się `KSEF_SNAPSHOT_POLICY_NOT_APPROVED`, bo model nie zawiera zatwierdzenia
dokładnej wersji. Nie ma automatycznej regeneracji, nowego idempotencyKey, usunięcia snapshotu ani rezerwacji.
Worker zapisuje `RECONCILIATION_REQUIRED` i bezpieczny kod w historii. Duplikat dostawy jest no-op.

| Sytuacja | Działanie |
| --- | --- |
| Drift / brak approval przed auth | Zachować operację/DB/storage, odczytać status i kod; nie tworzyć nowej próby |
| Crash przed outbox commit | Rollback DB, brak aktywnej wiadomości; storage orphan możliwy, nie usuwać automatycznie |
| Crash po commit / restart PREPARED | Claim, ta sama bramka, bez auth/SEND |
| Restart AUTHENTICATING/OPENING/OPENED/SENDING/CLOSING | Reconciliation, bez ponawiania efektu |
| Timeout po możliwym SEND | Reconcile hash/ref w tej samej sesji, bez drugiego SEND |
| Reconciliation bez sessionReference | Diagnostyka pozostaje lokalna; brak auth i wyszukiwania nowych faktur |
| Wyczerpane READ | Kontrolowane resumeReads po terminie, tylko istniejące ref i odczyty |
| ACCEPTED bez SAP/UPO | Osobny writeback/sweeper i UPO; ACCEPTED pozostaje; bez nowego SEND |
| VERSION_CONFLICT | Nie zmieniać ExpectedVersion, nie resetować rezerwacji, decyzja operatora |

Nie ma ścieżki automatycznego odblokowania już zatrzymanej operacji po przyszłej akceptacji ADR.
Potrzebny kontrolowany projekt recovery/approval z zachowaniem audytu i tego samego klucza.
Lease 5 min i finalizacja tokenem nie stanowią ogólnej gwarancji exactly-once przy bardzo długim
wywołaniu sieciowym. Przed odblokowaniem SEND trzeba zweryfikować też retry transportu JDK/SDK
i przeniesienie wire-testów IN-04 na rzeczywisty produkcyjny klient SEND; test dekoratora nie wystarcza.

## Decyzja i pierwsza przyszła próba

Porównanie A/B/C, gwarancje, RAP/CAP/UI5, recovery i writeback: [ADR-0010](../decisions/ADR-0010-outbound-approved-snapshot-proposal.md).
Rekomendacja C jest **PROPOSED**. Nie wykonano zmian RAP ani CDS/HANA. Approval wymaga nowych metadanych
CDS/storage i testów; odpowiedni kontrakt writebacku może wymagać osobnej zmiany RAP.

Przyszła procedura, **niewykonana i obecnym kodem zablokowana**:

1. Właściciel wybiera gwarancję spójności i writeback. Osobne zlecenie implementacji approval/schema/UI;
   review, offline PASS i CI dokładnego HEAD; nie usuwać guardu wyłącznie dla przepuszczenia eksperymentu.
2. Osobna zgoda na H01 READ/preflight konkretnego syntetycznego UUID, principal i zakres NIP/spółka.
   Potwierdzić poprawne dane, brak wcześniejszego SEND i środowisko/storage; bez użycia requestSend jako dry-run.
3. Operator ogląda i zatwierdza dokładne utrwalone bajty, SHA, długość, kompozycję i PartyData zgodnie z
   wdrożoną polityką. Oddzielnie zatwierdzić pojedynczy SEND i ewentualny SAP writeback, runtime i credentiale.
4. Dokładnie jedna operacja; po timeout najpierw status/reconciliation. Potwierdzić rzeczywiste ACCEPTED
   z numerem/hash/ref, niezależne UPO i wynik writebacku. Nie resetować DB/storage i nie ponawiać SEND.

**H01 preflight: kod offline przygotowany do osobnego zatwierdzenia READ; runtime/dokument niezweryfikowane.**
**Pierwszy KSeF TEST SEND: NO-GO.** Bieżąca sesja nie wykonywała KSeF ani SAP LIVE, deployu, HANA/HDI.
Owner-reported XAdES/GET z IN-04 nie jest wynikiem tej sesji i nie został powtórzony.

Źródła sprawdzone przed zmianą: [CAP Event Queues](https://cap.cloud.sap/docs/java/event-queues),
[CAP Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services),
[oficjalny SDK MF](https://github.com/CIRFMF/ksef-client-java). Wersje projektu pozostały przypięte:
CAP5.2.0 / Boot4.1.1 / SDK3.0.29; nie zmieniano kontraktu SDK ani wywoływano endpointów MF.
