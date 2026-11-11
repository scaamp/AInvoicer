---
title: "ORCH-04 — integracja CAP-HANA-01R + KSEF-IN-03A i bramka deploymentu"
date: 2026-11-11
type: journal
status: zakonczony
tags: [ainvoicer, journal, integration, review, ksef, inbound, cap-hana-01, hana, deployment-gate]
---

# ORCH-04 — integracja PR #13 i PR #14

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[KSEF-IN-03A — przewodnik](../guides/KSEF_IN_03A_GLOBAL_API_BUDGET.md) ·
[KSEF-IN-03A — dziennik](2026-11-11-ksef-in-03a-global-api-budget.md) ·
[CAP-HANA-01R — bramka runbooka](../guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md#cap-hana-01r--aktualna-bramka-recovery-1111-2035-cet) ·
[CAP-HANA-01R — dziennik](2026-11-11-cap-hana-01-first-persistence.md#cap-hana-01r--niezależna-weryfikacja-codex-2035-cet)

## Date / Branch / Goal

11 listopada 2026, Europe/Warsaw, Claude Code jako orkiestrator. Osobny worktree
`D:\AInvoicer-orch-04` (detached od `origin/main`). Cel: niezależny review dwóch PR, scalenie
wyłącznie PR spełniających bramki, regresja zintegrowanego `main`, jedna aktualizacja dokumentacji
i przygotowanie bramki GO/NO-GO dla CAP-HANA-01R. **Bez** abort/deploy/restartu CF, DDL/DML HANA,
provisioningu, LIVE KSeF i SAP WRITE.

## Initial State

- `origin/main=319adbf77941f6f0d9edf4fcb9af08e80ee83bc8` (zgodny z oczekiwanym).
- PR #13 `feature/ksef-in-03a-global-api-budget` HEAD `8d69d74ceaa83a7a7aa562ac827f131abd5fc8b2`:
  open, not draft, mergeable/clean, base `319adbf`, 6 plików; check `offline` **completed/success**.
- PR #14 `feature/cap-hana-01r-deployment-recovery-review` HEAD `7e9735a29452a5228ab32abfa45caee12a9d642c`:
  open, **draft**, mergeable/clean, base `319adbf`, 2 pliki docs; check `offline` **completed/success**
  (wcześniej IN PROGRESS).
- Główny checkout `D:\AInvoicer`: `main=f04fb56` (za origin), nieśledzony `cap-java/db/documents.cds`
  (236 B, SHA-256 `8d5b3e58…3e3`). Nie przełączano, nie pullowano i nie dotykano tego checkoutu.
- Odczyt GitHub przez publiczne REST API bez tokenu; `gh` niedostępne, credentiali nie odczytywano.

## Code review — PR #13 (KSEF-IN-03A)

Przeczytano całość `InboundApiBudget`, `ApiBudgetRepository`, `InMemoryApiBudgetRepository`,
39 testów, przewodnik i dziennik; sprawdzono `KsefTaxpayerContext`, `KsefSdkConfiguration`
i jedyne miejsce wywołań `sdk.queryInvoiceMetadata`/`sdk.getInvoice` (`KsefInboundClient`).

| Obszar | Wynik |
| --- | --- |
| Atomowość `transact` | Monitor obejmuje odczyt, decyzję i zapis; wyjątek w transition nie zapisuje stanu. Funkcja czysta (poza UUID/clock), więc dopuszcza ponowne obliczenie przy CAS. **OK** |
| Okna przesuwne | Okno `(now-d, now]`, termin `active[n+p-m]+d` wyliczony poprawnie; wszystkie okna naraz, wygrywa najpóźniejszy termin. **OK** |
| In-flight / complete | In-flight obciąża każde okno bez względu na wiek; complete przesuwa `chargedAt` na koniec (konserwatywnie). Tylko właściciel permitu w tym środowisku może zwolnić slot. **OK** |
| Konkurencja | Jedno repo → brak przekroczenia (testy 24 wątków, także dwie polityki). Różne repo/JVM → brak gwarancji, jawnie przetestowane i opisane. **OK dla zakresu offline** |
| NIP / endpoint / środowisko | Bucket = `(environment, NIP, operation, egressGroup)`, bez roli; agregat i in-flight per środowisko; środowiska rozdzielne. **OK** |
| Utrata potwierdzenia commit | DEFER, rezerwacja spalona (nadmiarowe, nie niedomiarowe liczenie). **OK, fail-closed** |
| Niedostępne repozytorium | DEFER `STATE_UNAVAILABLE`, bez lokalnego fallbacku i bez cause w wyniku. **OK** |
| Cofnięcie zegara / konfiguracja | DEFER do watermarku; błędna konfiguracja → REJECT bez dostępu do repo; zmiana polityki → `POLICY_MISMATCH`. **OK** |
| Retry-After | Tylko wydłuża blokadę całego środowiska; brak nagłówka = null, fallback po stronie adaptera HTTP. **OK** |
| Logi / dane | `Request.toString` zredagowany (także w `Attempt`), brak loggera, brak credentiali/XML w stanie. **OK** |
| Testy vs implementacja | Asercje odpowiadają kodowi; brak sleep, deterministyczne bariery. **OK** |

**Blokery: brak.** Uwagi niekrytyczne (bez zmian w PR):

1. *Low* — `complete()` zwracające `false` (cofnięcie zegara, awaria repo) nie utrwala przekazanego
   Retry-After. Javadoc i przewodnik wymagają wtedy globalnego wstrzymania dispatch; do zrobienia przy integracji.
2. *Low* — gdy okno blokują same in-flight albo osiągnięto `maxConcurrent`, decyzja ma `retryNotBefore=null`
   mimo znanego cooldownu. Kierunek konserwatywny; dotyczy tylko precyzji planowania.
3. *Low* — granica okna jest wyłączna i opiera się na zegarze JVM. Bez autorytatywnego czasu DB i przy
   rozjeździe zegarów konfiguracja powinna mieć zapas względem limitów MF.
4. *Info* — przepełnienie `Instant` przy skrajnej konfiguracji daje DEFER `STATE_UNAVAILABLE`, nie REJECT
   `INVALID_CONFIGURATION` (fail-closed, mniej precyzyjna diagnostyka); błąd konfiguracji jednego środowiska
   odrzuca wszystkie.

Ryzyka przyszłej integracji (świadomie odłożone, nie warunek merge offline):
osierocone permity bez TTL i bez API odzyskania; brak HANA adaptera i czasu DB; permit nie jest technicznie
związany z jednym HTTP (rezerwacja musi być w supplier `ReadRetryPolicy`, przy `KsefInboundClient`);
auth/challenge/redeem/refresh/certyfikaty poza budżetem; brak health/recovery i epoch/fencing po niepewnym
complete; skok zegara w przód; bardzo duży Retry-After bez limitu w polityce; brak koordynacji z innymi
klientami tej samej pary kontekst/IP.

## Review — PR #14 (CAP-HANA-01R)

| Kryterium | Wynik |
| --- | --- |
| Zakres | Wyłącznie runbook i dziennik CAP-HANA-01 (157+/2-). **OK** |
| SHA artefaktów | Niezależnie przeliczone w tej sesji: 0.7.3 `196b9b8c…1570` (67 562 976 B), rollback 0.7.2 `00e31c97…f962`, extension `f11b3196…cd14e` (599 B, blob `4e61b79` = `origin/main`), rollback extension `4c6afb70…05b7`. Worktree wydania czysty na `4630f89`. **OK** |
| Komendy | Deploy i rollback z `--retries 0`; rollback z `--version-rule ALL`; abort jako osobna operacja. **OK** |
| `d110c171…` | Odczyt CF w tej sesji: nadal aktywna **ERROR** — wymaga abort przed nowym deploymentem. **OK** |
| Brak sugestii wdrożenia | Status `recovery-go-for-owner-approval-not-deployed`, „CAP nadal na starym runtime”. **OK** |
| Stare archiwa | Wskazuje tylko 0.7.3/196b9b8c; odrzucone `6a92fb78…` i `be7bdbe6…` nie są używane. **OK** |
| Usługi/koszty/migracje | Tylko 3 istniejące usługi, brak provisioningu, undeploy, CSV i skryptów migracji. **OK** |
| Przerwa i rollback | Opisane przerwy CAP/AppRouter oraz osobny rollback bez cofania schematu. **OK** |
| JDBC/HDI/SQL | Wyraźnie PENDING; logi HDI nie zastępują SQL. **OK** |

**Blokery: brak.** Ryzyko operacyjne: zatwierdzany extension leży w roboczym drzewie `D:\AInvoicer`;
przed operacją B ponownie przeliczyć SHA (runbook to wymaga). Flagi draft nie można było zmienić bez
uwierzytelnionego API; ocena: treść jest zakończonym preflightem dokumentacyjnym, więc nadaje się do
scalenia bez sugerowania wykonanego deploymentu.

## Integration

| Krok | Wynik |
| --- | --- |
| Merge PR #13 | `--no-ff` dokładnego `8d69d74` → `528a973`; drzewo identyczne z przetestowanym HEAD PR |
| Merge PR #14 | `--no-ff` dokładnego `7e9735a` → `be97a20`; diff `319adbf..be97a20` = dokładnie 8 plików obu PR |
| Push | Zwykły push bez force (odrzucony, gdyby `main` się przesunął; odpowiednik `expected_head_sha`) |

Nie scalano innych gałęzi/worktree (w tym `feature/cap-hana-01r-deployment-recovery` Claude Code
`72bd8c6`, KSEF-STOR, BLOB-LAB). Wyniki CI po pushu: [GitHub Actions](https://github.com/scaamp/AInvoicer/actions).

## Tests (lokalnie, zintegrowane drzewo `be97a20`)

Toolchain: Microsoft OpenJDK 25.0.4.1, Maven 3.9.16, Node 24.13.0.

| Polecenie | Wynik |
| --- | --- |
| `mvn -B clean verify -Dcds.cds.skipVersionCheck=true` (cap-java) | **BUILD SUCCESS**, exit 0, 2:44 min; **468 testów: 462 PASS / 6 skipped (Azurite) / 0 failures / 0 errors** (potwierdzone z XML Surefire) |
| w tym `InboundApiBudgetTest` | **39/39 PASS** |
| `npm ci --ignore-scripts` + `npm rebuild mbt`; `npm test` (deploy) | **31 PASS / 1 skipped (brak Go) / 0 FAIL** |
| `npm run test:cf-runtime` (deploy, JAR z tego builda) | **10/10 PASS** |
| `verifyArchive` zatwierdzanego HANA 0.7.3 i smoke rollback 0.7.2 (odczyt, bez odbudowy) | **PASS**, HANA 14 tabel / 6 indeksów / 4 widoki |
| `git diff --check 319adbf..HEAD` | **PASS** (także drzewo robocze przed commitem dokumentacji) |
| Heurystyczny skan sekretów zmienionych plików | `node deploy/scripts/check-secrets.mjs` (śledzone + staged pliki): **PASS**, heurystyka, nie audyt credentiali |

Testy H2/mock nie są dowodem działania HANA. Profil `hana-integration` i tagi LIVE nieaktywne.
MTAR nie odbudowywano: delta runtime względem `4630f89` to wyłącznie 3 niepodłączone klasy budżetu
(brak adnotacji Spring, HTTP, sleep i referencji spoza pakietu), więc zatwierdzany 0.7.3 ich nie zawiera.

## Read-only CF (19:51 UTC)

`cf target`: EU20, org `National Center for Cryptography_dev-v2xzij9s`, space `dev`.
`cf mta-ops`: `d110c171-ddf8-11f1-8764-eeee0a818911` DEPLOY **ERROR** (aktywna).
`cf apps`: `ainvoicer-srv` i `ainvoicer-approuter` started 1/1, `ainvoicer-db-deployer` stopped 0/1.
`cf services`: `ainvoicer-db` (hdi-shared) związany wyłącznie z deployerem; `ainvoicer-hana-dev` hana-free.
Parametry brokera (wyłącznie wybrane pola): HANA `serviceStopped=false`, HDI `database_id=2161fe76-…d49e`.
Nie odczytywano credentiali, VCAP ani `/env`; brak mutacji. SQL health HANA nie był testowany.

## BLOB-LAB-01

Nie zmieniono przewodnika ani zakresu laboratorium, nie dodano CDS/HDI/infrastruktury i nie oznaczono
postępu. `D:\AInvoicer\cap-java\db\documents.cds` pozostał nieśledzony i niezmieniony (SHA-256 przed i po).

## Next Step

Decyzja właściciela o dwóch osobnych operacjach z
[bramki runbooka](../guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md#cap-hana-01r--aktualna-bramka-recovery-1111-2035-cet):
A — abort `d110c171…`, B — deployment `196b9b8c…` + extension `f11b3196…` z `--retries 0`.
Syntetyczne DML, restart i testy konkurencji wymagają osobnej zgody.
