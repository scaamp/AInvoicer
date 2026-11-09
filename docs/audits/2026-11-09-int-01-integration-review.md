---
title: "INT-01 — review integracji i plan scalenia"
date: 2026-11-09
type: audit
status: zakonczony
tags: [ainvoicer, audit, integration, security, ksef, inbound, git]
---

# INT-01 — review integracji

[Dziennik](../journal/2026-11-09-int-01-branch-integration.md) · [STATUS](../STATUS.md)

## Zakres i wynik

Merge CAP-07C `e850a69` i KSEF-IN-01 `344a67b`, wspólna baza `9d7a380`,
merge commit `350e64392a0eccacb30a30921606f8e106a9fb90`. Bez konfliktów, squash i utraty historii.
INBOUND wnosi 37 plików: 25 produkcyjnych Java, 9 testowych Java, XML fixture i 2 dokumenty.
Nie zmienia kodu OUTBOUND, modeli CDS, POM, konfiguracji Spring, UI5 ani infrastruktury.
INT-01 dodaje jeden test rzeczywistego kontekstu CF/XSUAA: brak beanów INBOUND i brak publikacji
wewnętrznych typów w uwierzytelnionym `$metadata`. Nie było potrzeby zmiany kodu produkcyjnego.

**BLOCKER integracyjny: 0.** Lokalna regresja: 321 testów Maven, bez failures/errors/skips;
77 testów INBOUND, pozostałe 244 obejmują bazowe 243 CAP-07C oraz nową regresję izolacji.
Wynik zdalnego CI i publikację zapisuje dziennik; lokalny PASS nie oznacza LIVE acceptance.

## Macierz review

| Obszar | Dowód i ocena |
| --- | --- |
| Nazwy/DTO | INBOUND we własnym pakiecie; osobny `inbound.KsefEnvironment` nie zmienia kontraktu OUTBOUND; kompilacja PASS. |
| Kryptografia | Brak nowego auth/szyfrowania; SHA-256 identyfikuje treść. JWT dostarczany przez zaufany backend, nie z payloadu OData. |
| FA(3) | StAX odczytuje ograniczony zestaw pól; istniejący generator i walidator XSD OUTBOUND zachowane. Parser nie jest pełnym walidatorem XSD. |
| Wersje | CAP 5.2.0, Boot 4.1.1, SDK MF 3.0.29, security 4.1.2 bez zmian POM/locków. |
| Błędy/logi | `InboundFailure` nie przenosi przyczyny/body/URL SDK; token i XML mają redagowane `toString`. Brak loggerów w pakiecie INBOUND. |
| Spring/startup | Żadnej adnotacji komponentu, schedulera, listenera ani konfiguracji INBOUND; test kontekstu CF potwierdza brak beanów. SDK crypto nadal lazy. |
| Konteksty | Klient porównuje token/context/environment przed HTTP; metadane, granty i checkpointy rozdzielają środowisko/NIP/rolę. Nie jest to walidacja claimów JWT ani autoryzacja użytkownika. |
| XML/XXE | DTD i encje zewnętrzne OFF, resolver odmawia, namespace/encoding/rozmiar/głębokość/liczba elementów i wierszy ograniczone; testy negatywne PASS. |
| Dostęp do XML | Brak endpointu; przyszły endpoint wymaga tożsamości i grantów. `issuedFor` jest kontraktem dla zaufanego kodu backendu. |
| Retry/timeout | Tylko odczyty, ograniczone próby, backoff/jitter, sekundy/data Retry-After, długi wait oddawany przyszłemu schedulerowi. HTTP redirect NEVER, timeout SDK 20 s. |
| HWM | Sink musi zakończyć cały segment; wyjątek nie przesuwa checkpointu. CAS wykrywa konkurencję. Testy błędu sinku/strony, truncation i restartu PASS. |
| DB/migracja | Zero zmian CDS/DB. Kontrakt checkpointów bez produkcyjnego repozytorium; in-memory tylko w testach. |
| OUTBOUND | Auth/sesje/worker/accepted-writeback/send handler/SAP adapter, rezerwacje, outbox, FA(3), snapshot/hash pozostają w historii i przechodzą testy. |
| SAP WRITE | Domyślnie OFF; consent CAP-04D chroni 4 seedy, dokładny UUID/NIP/spółkę i nowy fixture; stały WriteRequestId, CSRF i kontrola wersji potwierdzone testami lokalnymi. |
| XSUAA/UI5 | Guardy, JWT, role i pary NIP/spółka fail closed; mock OFF na CF. RAP/CAP dual-backend zachowany; capabilities i AI OFF, CSP self, regresja redirectów credentiali PASS. |
| CI | Maven wykrywa wszystkie `*Test`, także INBOUND. Istniejący workflow bez filtra pakietów wystarcza; brak nowego workflow i sekretów. |

## Ryzyka i bramki przed uruchomieniem procesu

| Waga | Ustalenie | Działanie |
| --- | --- | --- |
| HIGH, zastane | UI5 tooling: npm ci raportuje 74 advisories (2 low/15 moderate/54 high/3 critical). To zależności budowania, nie nowe zależności INBOUND. | Osobny audyt i migracja toolingu; nie wykonywano automatycznego audit fix. deploy i AppRouter: 0 advisories w instalacji. |
| HIGH, bramka LIVE | Brak trwałego stagingu/checkpointów/lease i magazynu XML; smoke H2 nie nadaje się do workflow. | KSEF-IN-02 przed włączeniem schedulera, wybór DB/storage i testy trwałości. |
| HIGH, bramka LIVE | Business-user SSO H01, role A/B i CAP-04D niezaliczone w INT-01; dotychczasowy CF nie dostał poprawek CAP-07C. | Osobno zatwierdzony redeploy i CAP-07B.2; osobne upoważnienie WRITE/SEND. |
| MEDIUM | SDK `getInvoice` buforuje odpowiedź przed kontrolą 3 MiB; limit i timeout nie są twardym limitem pamięci podczas transferu. | Przed LIVE ocenić limit transportowy i współbieżność; nie deklarować odporności na nieograniczone body. |
| MEDIUM | `issuedFor` ufa wywołującemu, a środowisko klienta jest parametrem; binding rzeczywistego SDK do tego środowiska musi zapewnić konfiguracja. | KSEF-IN-02: adapter wyłącznie zaufanego auth i konfiguracja TEST; bez tokenu/contextu z UI. Obecnie brak podłączenia. |
| MEDIUM | Publiczny planer porównuje początek okna i typ daty, nie cały kontrakt query; `InvoiceSyncRunner` sam używa dokładnego query okna. | Przy rozszerzeniu wywołań w IN-02 uszczelnić zgodność roli/to/restrictHwm; obecna ścieżka nie podmienia query. |
| LOW | Retry-After o skrajnej liczbie sekund może przepełnić `Instant`; brak niebezpiecznego retry, ale utrata kategorii bezpiecznego błędu. | Harden parser nagłówka przy kolejnej zmianie transportu INBOUND. |
| LOW | Parser odczytu nie zastępuje pełnej walidacji XSD ani pełnej obsługi wariantów FA(3); kwoty metadanych SDK to Double. | Kwoty autorytatywne z XML; pełna walidacja w stagingu, jawne unsupported variants. |

Brak BLOCKER/HIGH wprowadzonego przez sam merge. Powyższe bramki wykluczają deklarację gotowości produkcyjnej.
Testy używają syntetycznych tokenów i localhost; heurystyczny skan śledzonych plików przeszedł, nie jest audytem całej historii credentiali.

## Rozmiar i izolacja artefaktów

Executable JAR: **75 065 650 B**; INBOUND: 48 plików `.class`, 173 439 B surowo / 71 228 B skompresowane.
MTAR: **67 366 302 B** (historyczny CAP-07C: 67 295 265 B; różnica 71 037 B, pomiar historyczny z dziennika).
Brak nowych bibliotek. MTAR ma tylko CAP i AppRouter z zasobami UI5; XSUAA i Destination jako existing-service.
Bez DB-deployera, HDI i nowych usług; SEND/WRITE/sweeper OFF. JAR/MTAR nie są śledzone przez Git.

## PR #1 — dokładne rozwiązanie konfliktu bez aktualizacji brancha

GitHub: OPEN, mergeable=false. Aktualne `origin/main` = `552fdb9`, CAP-06.1 = `ed35935`.
`git merge-tree --write-tree origin/main origin/feature/cap-06-ui5-09-dual-backend`
wykazał wyłącznie konflikt `docs/README.md` (tree `db57f54aee1633daba86e3e85d4435f7acabb8c7`).
Niezależny tymczasowy index zawiera rozwiązane tree **`5bcec93ff6ad623bb9846b0bf4a94307a12e51b9`**.
Sprawdzono: tylko README zmienione względem konfliktowego tree; wszystkie bloby `docs/architecture/` identyczne z main;
wszystkie odnośniki CAP-06 i CAP-06.1 zachowane. Bez commita, zmiany refs, checkoutu lub modyfikacji PR #1.

Rozwiązanie fragmentu w sekcji „Od czego zacząć”: usunąć markery konfliktu, zachować kolejno:

```markdown
- [Docelowa architektura bezpieczeństwa — dwa diagramy SVG, granice tożsamości i relacje SAP IAM/DCL](architecture/README.md).
- [CAP-06.1 — hardening, pełna regresja i gotowość PR; smoke FLP nadal KNOWN BLOCKER](journal/2026-11-08-cap-06-1-hardening.md).

- [CAP-06/UI5-09 — RAP LIVE + lokalny CAP, poprawki runtime i filtrów; IAS/WRITE nadal BLOCKED](journal/2026-11-08-cap-06-ui5-09.md).
```

Jeśli właściciel wybierze scalenia etapowe: nowy review worktree z CAP-06.1 → merge aktualnego main `--no-commit`
→ powyższe rozwiązanie → diff/review → commit/push dopiero w zatwierdzonym zakresie. Nie wymaga wyboru ours/theirs.

## Plan merge dla właściciela

Historia przed INT-01: CAP-06.1 (4 commity względem main) → CAP-07 (3) → CAP-07.1 (5)
→ CAP-07B (9, w tym merge istniejącego main) → CAP-07C (6) → INT-01 (4 odziedziczone commity INBOUND + merge + regresja/dokumentacja).
Nie trzeba osobnego PR dla każdej nazwy sprintu, żeby zachować te commity.

**Rekomendowane uproszczenie: dwa PR-y do main z merge commit, bez squash/rebase.**

1. Właściciel przegląda zbiorcze CAP-07C → main (zawiera CAP-06.1/07/07.1/07B/07C i istniejący merge dokumentacji main).
   `origin/main` jest przodkiem CAP-07C; konflikt PR #1 jest już rozwiązany historycznie w CAP-07B.
   Compare: https://github.com/scaamp/AInvoicer/compare/main...feature/cap-07c-autonomous-hardening .
   Po akceptacji owner tworzy/scaleniem takiego PR wprowadza cały stos; PR #1 i #2 stają się zastąpione i owner może je zamknąć.
2. Dopiero po scaleniu wcześniejszego stosu retarget INT-01 z CAP-07C na main, ponownie sprawdzić diff i CI; owner wykonuje merge commit.

Alternatywa etapowa: PR #1 z rozwiązaniem powyżej → CAP-07 → CAP-07.1 → CAP-07B → PR #2/CAP-07C → INT-01.
PR #2 obecnie OPEN i mergeable=true względem CAP-07B; jego scalenie przesunie CAP-07B, więc wymaga świadomej decyzji.
INT-01 jest przygotowany względem CAP-07C. Żaden istniejący PR ani branch nie został zmieniony.
Nie utworzono nadmiarowych PR ani nie wykonano merge do main.

## Źródła kontraktu

Weryfikacja źródeł: [CAP Java Event Queues](https://cap.cloud.sap/docs/java/event-queues),
[SDK MF przypięty do commita 3c3a899](https://github.com/CIRFMF/ksef-client-java/blob/3c3a899541074aa4bd944636b25659aef7d23da7/ksef-client/src/main/java/pl/akmf/ksef/sdk/api/DefaultKsefClient.java),
[HWM MF](https://github.com/CIRFMF/ksef-api/blob/main/pobieranie-faktur/hwm.md).
Zweryfikowano implementację `getInvoice`/`queryInvoiceMetadata` względem źródła SDK, nie wdrożenie LIVE API.
