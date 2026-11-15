---
title: "E2E-01C — Approved Immutable Snapshot, projekt do decyzji"
date: 2026-11-15
type: guide
status: proposed-not-implemented
tags: [ainvoicer, e2e-01c, outbound, approval, snapshot, security, rap]
---

# E2E-01C — zatwierdzenie konkretnych bajtów FA(3)

[ADR-0010 — PROPOSED](../decisions/ADR-0010-outbound-approved-snapshot-proposal.md) ·
[Review ORCH-09](../journal/2026-11-15-orch-09-outbound-hana-integration.md) ·
[E2E-01B](E2E_01B_OUTBOUND_SEND_HARDENING.md)

**Projekt do decyzji właściciela.** Nie dodano encji CDS, endpointów ani approval w runtime.
`KsefInitialSendGuard` nadal bezwarunkowo blokuje pierwszy auth/open/SEND.
Akceptacja tego dokumentu nie stanowi zgody na implementację, deployment ani wysyłkę TEST.

## 1. Wybór gwarancji

| Wariant | Gwarancja i koszt | Ocena |
| --- | --- | --- |
| A — SAP freeze | SAP atomowo zamraża rodzica i wszystkie dzieci; trwały token, kontrola wszystkich UPDATE/DELETE, recovery po UNKNOWN i autoryzowane unfreeze | Najbliżej „aktualna, zablokowana faktura”, ale ingeruje w proces SAP i może pozostawić długą blokadę |
| B — niezmienna wersja biznesowa SAP | SAP publikuje kompletne, stabilne wersje kompozycji; wynik odnosi się do businessVersion, nie do mutowalnej kopii roboczej | Najsilniejszy model wersji, większy zakres RAP i migracji |
| C — immutable snapshot CAP + operator | Operator zatwierdza dokładne bajty i dowód obserwacji SAP/PartyData. Edycja SAP nie zmienia zatwierdzonego dokumentu | Rekomendacja warunkowa; wymaga świadomej zgody na semantykę zatwierdzonej obserwacji |

Rekomendacja: **C z odmową po wykrytym drifcie przed pierwszym efektem i osobną ochroną writebacku**.
Snapshot nie oznacza zamrożenia SAP. Obecne trzy READ CQN i `SAP-OBSERVATION-v1` nie dają atomowej
wersji kompozycji ani dowodu kompletności wszystkich stron. W UI i audycie nazwa musi brzmieć
„obserwacja kompozycji”, dopóki SAP nie udostępni rzeczywistego tokenu wersji całej kompozycji.

Warunek przygotowania: udokumentowane limity/stronicowanie dzieci i referencji, detekcja obcięcia,
ponowny pełny odczyt oraz jawna akceptacja ograniczenia atomowości przez właściciela. Jeśli wymagana
jest gwarancja aktualności w chwili SEND, wybrać A/B lub atomowy eksport SAP połączony z C.
Dwa zgodne READ, Header ETag i hash dzieci nie spełniają takiej gwarancji (ABA i mieszany READ).

## 2. Niezmienny materiał i model CDS — wyłącznie projekt

Nazwy poniżej są propozycją nowych prywatnych encji, a nie istniejącym kontraktem.
Duże XML, znormalizowana obserwacja i PartyData trafiają do zatwierdzonego trwałego storage;
DB przechowuje hashe, rozmiary, referencje i relacje. Brak dostępu przez BlobService/LAB.

| Element | Minimalne pola / ograniczenia |
| --- | --- |
| Rozszerzenie `KsefInvoiceSnapshots` | `evidenceFormatVersion`, `compositionSha256`, `partyDataSha256`, `partyDataRevision`, `sapCompositionVersion` nullable z typem dowodu, `observedAt`, `generatedAt`, `evidenceStorageRef`, `evidenceSha256`, `previewFormatVersion`; istniejące `xmlSha256`, `xmlByteLength`, `schemaVersion`, source/context pozostają |
| `KsefSnapshotApprovals` — INSERT-only | UUID `ID`, `snapshotId`, `operationId`, `environment`, `taxpayerNip`, `companyCode`, `xmlSha256`, `xmlByteLength`, `compositionSha256`, `partyDataSha256`, `evidenceSha256`, `policyVersion`, `principalIssuer`, `principalSubject`, `identityZone`, `approvedAt`, `expiresAt`, `authorizationRevision`; wszystkie obowiązkowe dowody zamrożone przy INSERT |
| `KsefApprovalEvents` — INSERT-only | `ID`, `approvalId`, monotoniczny `sequence`, typ APPROVED/REVOKED/EXPIRED/DISPATCH_ARMED/OUTCOME_RECORDED, actor/kontekst, czas serwera, wersja polityki, bezpieczny reasonCode, correlationId; UNIQUE `(approvalId, sequence)` |
| `KsefApprovalDispatch` — prywatny koordynator CAS | PK `operationId`, `approvalId`, `snapshotId`, `state`, `revision`, `claimToken`, `leaseUntil`, `dispatchArmedAt`; jeden aktywny approval na operację przez PK i CAS. Zmiana stanu i audit event w jednej transakcji |

Approval jest niezmiennym dowodem; odwołanie jest nowym zdarzeniem, nie UPDATE zgody. Mutowalny
koordynator jest projekcją synchronizacji, odtwarzaną i sprawdzaną względem audytu. Rekordy nie są
wystawiane przez ogólny CRUD. Nie używać wyłącznie `createdBy` ani UI `approved=true` jako dowodu.
Retencja, backup i ochrona przed zmianą przez administratora DB wymagają osobnej polityki:
INSERT-only aplikacji oraz content addressing nie oznaczają WORM ani kwalifikowanego podpisu.

Unikalność rezerwacji `(InvoiceUUID, TEST)` pozostaje. Nie dodawać jej słabszego zamiennika per hash.
Nowe indeksy/encje oznaczają osobny release HDI i **nową wersję G1**; nie edytować kontraktów
15/5/6/15 ani STOR04 16/5/6/16, by przepuścić schemat approval.

## 3. Principal, zakres i polityka

Każde prepare/preview/approve/revoke/status wymaga uwierzytelnionego business principal,
roli `KsefOperator` i zaufanej **pary NIP × CompanyCode** oraz TEST. Stosować istniejący
fail-closed tenant gate; nie tworzyć iloczynu kartezjańskiego dwóch list atrybutów.
Scope wynika z utrwalonej obserwacji SAP i przypisania użytkownika, nie z wartości payloadu.
Worker działa jako techniczny wykonawca wcześniej zatwierdzonego związania, nie podszywa się pod operatora.

Tożsamość audytu: stabilne issuer + subject + identity zone/tenant, czas serwera i korelacja.
Nie utrwalać JWT/refreshToken, haseł, pełnych claims ani traktować email/displayName jako klucza.
Rola administratora technicznego i token client-credentials nie mogą automatycznie zatwierdzać faktury.
Odmowa odczytu obcego snapshotu nie ujawnia jego istnienia, hasha ani referencji storage.

`policyVersion` wskazuje niezmienny, recenzowany zestaw zasad: mapper/XSD, wersję evidence/preview,
rounding, freshness, ważność zgody, drift, granicę odwołania i writeback. Nie ma flagi bypassu.
Zmiana polityki unieważnia nieuruchomione zgody według jawnej listy wspieranych wersji;
nie przepisuje istniejącego approval. Czas ważności oraz ewentualne four-eyes wymagają decyzji właściciela.
Rekomendacja pierwszego slice: pojedynczy uprawniony operator, bez samoczynnego przedłużania zgody.

Ważność uprawnień musi być sprawdzalna także po restarcie: wersjonowany rejestr autoryzacji/revocation
lub autorytatywny mechanizm ponownej weryfikacji przed dispatch. Sam historyczny JWT lub aktualne
uprawnienia technicznego workera nie dowodzą uprawnień zatwierdzającego. Brak takiego dowodu = STOP.
Nie dodawać ukrytych połączeń do IAM w transakcji DB; mechanizm trzeba wybrać i przetestować osobno.

## 4. Kontrakt procesu i UI5 — propozycja

1. `prepareSnapshot(invoiceUUID)` odczytuje zaufany RAP, wykonuje walidator E2E-01B, FA(3)/XSD,
   obserwację i drugi READ/PartyData. Utrwala immutable XML/evidence i oczekującą operację z rezerwacją.
   Bez zadania wykonującego SEND; status proponowany `AWAITING_APPROVAL`. Rollback DB może pozostawić
   adresowany hashem orphan w storage; jego obsługa bez automatycznego DELETE.
2. `getSnapshotPreview(snapshotId)` udostępnia autoryzowany preview z **utrwalonych bajtów**, numer,
   strony/pozycje, NIP/spółkę, sumy, źródło, czasy, SHA-256, rozmiar, snapshotId, observation token,
   PartyData revision/hash i policyVersion. Żaden aktualny READ SAP nie zastępuje treści preview.
3. `approveSnapshot(operationId, snapshotId, expectedXmlSha256, expectedEvidenceSha256, policyVersion, expectedRevision)`
   traktuje wartości klienta jako warunki zgodności, a nie źródło prawdy. Serwer ponownie sprawdza role,
   tuple i integralność. W jednej krótkiej transakcji: lock rezerwacja → operacja → koordynator,
   INSERT approval/event, CAS aktywnego związania i enqueue outbox. Duplikat tego samego żądania zwraca
   to samo approval; zmienione bajty/revision/policy = 409, bez drugiej operacji.
4. `revokeSnapshotApproval(approvalId, expectedRevision)` działa według sekcji 5. UI nie implementuje
   zgody przez ogólny PATCH ani dodatkowy przycisk wywołujący stary `requestSend`.

To projekt wersjonowanej zmiany istniejącego `requestSend`, nie plan pozostawienia równoległego
niechronionego wejścia. Stare wywołanie musi pozostać zablokowane albo dostać jawnie zmieniony kontrakt.
UI5 rozróżnia AWAITING_APPROVAL, APPROVED, AUTH_PREPARATION, REVOKED, EXPIRED, SEND_ARMED,
RECONCILIATION_REQUIRED, ACCEPTED, SAP_WRITEBACK_BLOCKED_CHANGED_COMPOSITION i UPO availability.
Odpowiedź z enqueue nie oznacza SEND/ACCEPTED. Po odświeżeniu UI pobiera trwały stan serwera.

Preview: bez zewnętrznych encji/DTD/XSLT, bez `innerHTML` z SAP, escapowanie tekstu, CSP, CSRF
i ograniczenia rozmiaru. Ewentualny surowy XML tylko autoryzowany download `attachment`, z bezpiecznym
MIME/nazwą i bez cache publicznego. PDF generowany wyłącznie z tych samych bajtów, z oznaczeniem,
że nie jest odrębnym źródłem. Nie umieszczać dokumentu/tokenów w URL, localStorage, logach ani telemetry.

## 5. Worker, odwołanie i wyścigi

Przed auth worker weryfikuje: integralność XML/evidence, długość, snapshot/op/reservation/approval,
scope TEST/NIP/spółkę, uprawnienia/politykę/expiry/revocation oraz brak wcześniejszego SEND i konkurenta.
Obsłużona odmowa utrwala jawny stan i kończy dostawę outboxa; nigdy nieskończone QUEUED/retry.
Nie regeneruje XML, nie zmienia idempotencyKey i nie zwalnia rezerwacji automatycznie.

Auth/open są wykonywane poza transakcją DB, z checkpointami istniejącej maszyny stanów.
Zgoda pozostaje odwoływalna podczas przygotowania auth/open. **Bezpośrednio przed SEND** druga kontrola
w jednej transakcji blokuje ten sam koordynator, sprawdza expiry/revocation i CAS → `SEND_ARMED`,
utrwala `DISPATCH_ARMED` oraz checkpoint SENDING. Tylko zwycięzca może raz wywołać adapter SEND.
Revoke i arm blokują te same wiersze w tej samej kolejności, z kontrolą affected rows.

| Wyścig / awaria | Wymagany rezultat |
| --- | --- |
| Revoke wygrywa przed arm | trwały REVOKED, brak SEND; jeżeli istnieje sesja, osobne audytowane recovery, bez ślepego close |
| Arm wygrywa | revoke zwraca 409 `SEND_ALREADY_ARMED`; UI nie obiecuje cofnięcia wysyłki |
| Approval wygasa / zmienia się polityka przed arm | brak SEND, trwały jawny powód i zakończona dostawa |
| Crash przed arm | odtworzenie tej samej zgody, tylko jeśli nadal ważna; efekty auth/open według recovery, bez automatycznego ponowienia niejednoznacznego POST |
| Crash po arm, także przed faktycznym HTTP | RECONCILIATION_REQUIRED; nie resetować arm i nie ponawiać SEND bez dowodu |
| Timeout SEND/close/redeem | obecne UNKNOWN/reconciliation, bez drugiego efektu; approval nie jest biletem do retry |
| Duplikat outbox / drugi worker / lease expiry | brak kolejnego SEND; CAS + trwały arm niezależny od krótkiego lease |

**Granica odwołania jest logicznym, trwałym arm przed HTTP.** Nie da się atomowo połączyć commit DB
z fizycznym wysłaniem pakietu do MF. Istnieje małe okno arm → wire, w którym revoke już odmawia.
Właściciel musi zaakceptować tę semantykę; nie obiecywać „odwołania aż do ostatniego bajtu”.
Testy zatrzymania JVM i retry rzeczywistego SDK/JDK są wymagane, sam lease ani mock adaptera nie wystarczą.

## 6. Zmiany SAP/PartyData i istniejące rekordy

Przed approval i przed pierwszym auth porównać pełną obserwację/PartyData z utrwalonym evidence.
Wykryta zmiana = `STALE_SNAPSHOT`, bez SEND i bez cichej podmiany bajtów. Przygotowanie nowej wersji
wymaga nowego preview/approval; przepięcie rezerwacji tylko jawnie i atomowo po dowodzie, że stara
operacja nie została armed i nie ma niejednoznacznych efektów. Zachować wszystkie wersje i audit.
Po auth/open ponownie skontrolować lokalną zgodę przed arm; wykryty nowy drift również blokuje.

Po arm zmiana SAP nie zmienia bajtów, approval ani korelacji operacji. ACCEPTED/KSeF number/UPO
dotyczą zatwierdzonego snapshotu, nawet jeżeli SAP później zmieniono. Nie ma automatycznego
SEND „nowszej wersji” tego samego InvoiceUUID. Korekta biznesowa wymaga osobnego procesu KSeF.
Stare snapshoty bez wymaganych dowodów pozostają zablokowane; zakaz backfillu zgody i auto-resume
operacji zatrzymanych przez `KSEF_SNAPSHOT_POLICY_NOT_APPROVED`.

## 7. Writeback — proponowane rozstrzygnięcie

**Nie zapisujemy wyniku do bieżącego dokumentu SAP, który zmienił się po approval, również tylko
w dzieciach lub PartyData wpływającym na fakturę.** CAP zachowuje ACCEPTED i UPO oraz prezentuje
wynik przypisany do konkretnego snapshotu. Status synchronizacji nie cofa wyniku KSeF.

Niezmieniony Header ETag nie wystarcza. Przyszły writeback do aktualnego nagłówka wymaga po stronie
SAP atomowego porównania wersji/odcisku **całej kompozycji** i istniejącego Header CAS w tej samej LUW,
z idempotentnym WriteRequestId. READ w CAP przed `ApplyKsefResult` nie zamyka okna TOCTOU.
Nie podmieniać ExpectedVersion na świeżą, nie omijać CAS, nie zmieniać InvoiceStatus.

Do czasu takiego kontraktu: nowe zatwierdzone snapshoty **bez automatycznego writebacku przez obecny
Header-only API**, nawet jeżeli odczyt CAP wygląda zgodnie. Osobna propozycja RAP może zapisywać
wynik do archiwalnej wersji/ref bez zmiany bieżącej faktury; nie twierdzimy, że taka encja istnieje.
Sweeper musi odróżniać tę politykę od legacy SAP_RAP, żeby nie uruchomił starej ścieżki po restarcie.
Recovery operacji wysłanych przed nową polityką zachować według ich istniejącego kontraktu.

## 8. Plan implementacji i kryteria odbioru

| Etap osobno zlecany | Wynik i bramka |
| --- | --- |
| C0 — decyzja | Akceptacja ADR-0010: semantyka obserwacji C, granica arm/revoke, freshness/expiry, principal, writeback i retencja. SEND nadal zablokowany |
| C1 — kontrakt i schema | CDS prywatnych approval/events/koordynatora, ograniczenia UNIQUE/CAS, migracja addytywna i nowy G1; bez automatycznego deploymentu |
| C2 — prepare/preview | Ten sam XML/evidence po restarcie, pełna autoryzacja tuple i bezpieczny preview, brak efektów SDK |
| C3 — approval/revoke/outbox | Idempotencja, atomowość DB/audytu/outboxa, rollback, revocation, expiry i zmiana uprawnień/polityki |
| C4 — worker | Podwójna kontrola zgody, trwały arm, brak ponownego SEND, legacy recovery; produkcyjny guard pozostaje do odrębnego release review |
| C5 — writeback | Wersjonowana polityka i blokada sweepera; osobno RAP atomic composition CAS lub świadomy CAP-only wynik |
| C6 — UI5 i security | Preview/approval/revoke/restart, jawne kody, A/B scope, CSRF/XSS/XXE, brak publicznego storageRef |
| C7 — release / pierwsza próba | Offline/CI, runtime storage/security/HANA acceptance i zatwierdzony H01 preflight. Dopiero osobna zgoda na jeden dokładny snapshot/SEND TEST i zakres writebacku |

Minimalna macierz testów: inny NIP/spółka/tenant/principal; brak roli lub token techniczny; cross-product
claims; podmiana snapshot/op/hash/length/evidence/PartyData/policy; preview inne niż bajty magazynu;
duplikat approval/revoke; approve vs revoke vs arm na dwóch połączeniach i dwóch JVM; expiry na granicy;
rollback audytu/enqueue; crash w każdym checkpoint; utrata lease; retry realnego HTTP/SDK po utracie
odpowiedzi; stary snapshot bez approval; dzieci zmienione bez Header timestamp; TOCTOU writeback,
CAS conflict i brak automatycznego writebacku sweepera; ACCEPTED i UPO mimo konfliktu SAP.
H2 potwierdza logikę; HANA locking/409006/restart i SAP atomic version wymagają osobnych pomiarów LIVE.

Źródła do implementacji (sprawdzone podczas ORCH-09):
[CAP security](https://cap.cloud.sap/docs/java/security),
[ChangeSet contexts](https://cap.cloud.sap/docs/java/event-handlers/changeset-contexts),
[persistent Event Queues](https://cap.cloud.sap/docs/java/event-queues),
[Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services).
Projekt pozostaje przy CAP 5.2.0 / Boot 4.1.1 / przypiętym SDK MF 3.0.29;
przed implementacją ponownie sprawdzić sygnatury i kontrakty. Nie wywoływano API KSeF ani H01.
