---
title: "Dziennik — SAP HANA Cloud free tier: decyzja i utworzenie instancji"
date: 2026-11-11
type: journal
status: zakonczony
tags: [ainvoicer, journal, btp, hana, hana-cloud, free-tier, costs, persistence]
---

# Dziennik — SAP HANA Cloud free tier

[Przewodnik krok po kroku](../guides/HANA_CLOUD_FREE_TIER_SETUP.md) · [ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md) ·
[KSEF-STOR-01](2026-11-11-ksef-stor-01.md) (po merge `feature/ksef-stor-01-durable-storage`) · [Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Date

2026-11-11 (Europe/Warsaw), sesja kontynuująca KSEF-STOR-01.

## Branch

Dokumentacja: `docs/hana-cloud-free-tier`, worktree `D:\AInvoicer-hana-01`, baza `origin/main = f093af5`.
Zmiany w BTP nie są związane z gałęzią Git.

## Goal

Ustalić, jak uzyskać trwałą bazę CAP na CF bez kosztów, i przeprowadzić właściciela przez utworzenie
instancji SAP HANA Cloud free tier. Granica: agent nie wykonuje zmian w BTP, nie zna hasła `DBADMIN`,
nie podłącza CAP i nie wykonuje deployu.

## Initial State

- Relacja z dokumentacji: inventory 2026-11-09 — `hana-cloud` tylko płatne `hana`/`relational-data-lake`, brak
  `hana-free`, brak `hdi-shared`; CF w trybie identity-smoke na H2 w pamięci.
- KSEF-STOR-01 tej samej sesji: Object Store `standard` jest płatny, darmowego planu nie znaleziono.
- Właściciel: „nie chcę ponosić żadnych kosztów”.

## Implementation

Przebieg decyzji:
1. Wyjaśnienie celu magazynu XML/UPO (analogia ArchiveLink/content repository, nie DMS).
2. Ocena PostgreSQL na prywatnym VPS: możliwy, ale wymaga ADR, zmiany guardu, TLS, firewalla i backupów — rezerwa.
3. Korekta: marketplace pokazuje tylko plany przypisane; `hana-free` mógł być dostępny w global account.
4. Właściciel w BTP (agent prowadził na podstawie zrzutów ekranu):
   - Entity Assignments → subaccount `dev`: stan wyjściowy `hana`, `relational-data-lake`, `hana-cloud-connection`, `tools`.
   - Add Service Plans → **`hana-free`** (Free Tier) → `Assigned (2 units)`.
   - Usunięcie płatnych `hana` i `relational-data-lake`.
   - Subskrypcja `tools` (HANA Cloud Central) i role collection *SAP HANA Cloud Administrator*.
   - Kreator: Cloud Foundry / space `dev`, `ainvoicer-hana-dev`, Free Tier 16 GB / 1 vCPU / 80 GB, auto-upsize OFF,
     repliki 0, połączenia tylko z IP CF regionu, wersja 2026.28, dodatkowo NLP + PAL (decyzja właściciela), bez mapowania.
   - SQL Console: zapytanie kontrolne.
5. Szczegóły i wartości: [przewodnik](../guides/HANA_CLOUD_FREE_TIER_SETUP.md).

## Changed Files

- `docs/guides/HANA_CLOUD_FREE_TIER_SETUP.md` — decyzja, procedura, parametry, eksploatacja, następne kroki.
- `docs/journal/2026-11-11-hana-cloud-free-tier.md` — ten dziennik.

STATUS, ROADMAP, README i runbook BTP celowo bez zmian: równolegle trwa CAP-07D (Codex) na tych obszarach;
aktualizacja należy do zadania integracyjnego (przewodnik §6.6).

## Commands Executed

```bash
cf target                      # odczyt; org/space poprawne
cf marketplace -e hana-cloud   # FAILED: token CLI wygasł — wymaga cf login --sso właściciela
cf marketplace -e hana         # FAILED: jw.
git fetch origin
git worktree add -b docs/hana-cloud-free-tier ../AInvoicer-hana-01 origin/main
git branch --unset-upstream
```

Wszystkie operacje w BTP cockpit / HANA Cloud Central wykonał właściciel w przeglądarce.

## Tests & Results

| Kontrola | Wynik | Rodzaj dowodu |
| --- | --- | --- |
| `hana-free` przypisany do `dev` | **PASS** — `Assigned (2 units)` | zrzut właściciela |
| Płatne plany HANA usunięte | **PASS** — pozostały `tools`, `hana-free`, `hana-cloud-connection` | zrzut właściciela |
| Kreator: Free Tier, parametry jak w przewodniku | **PASS** | zrzuty kroków 2, 5 i podsumowania |
| SQL `SELECT CURRENT_USER, CURRENT_TIMESTAMP, VERSION` | **PASS** — `DBADMIN`, `2026-11-11 08:57:28.089` (UTC), `4.00.000.00.1793723943` | zrzut SQL Console |
| Zapytania pamięć/dysk/schematy | **NIE RAPORTOWANO** | — |
| Własny odczyt CLI marketplace | **FAIL/BLOCKED** — wygasły token | wynik CLI |
| Koszty (Costs and Usage) | **NIE SPRAWDZONO** po utworzeniu | do wykonania przez właściciela |
| Podłączenie CAP / HDI / deploy | **NIE WYKONANO** | poza zakresem |

Agent nie miał bezpośredniego dostępu do bazy (połączenia tylko z IP CF; brak credentiali) — weryfikacja
działania opiera się na zrzucie właściciela.

## Problems Encountered

- Entity Assignments startuje bez subaccount — trzeba go wybrać w polu filtra.
- Zakładka *Cloud Foundry* w kreatorze nieaktywna do zalogowania w CF (link w kreatorze).
- Filtr ról na zakładce użytkownika przeszukuje tylko przypisane role — mylący pusty wynik.
- Błędny wcześniejszy wniosek „HANA tylko płatna” — skorygowany w przewodniku §1.

## Decisions Made

- Trwałość CAP na CF: **SAP HANA Cloud free tier** zgodnie z ADR-0007 (bez nowego ADR dla samej bazy).
- PostgreSQL na VPS — rezerwa, gdyby free tier okazał się niewystarczający.
- Płatne plany HANA usunięte z subaccount jako zabezpieczenie kosztowe.
- NLP/PAL włączone na prośbę właściciela do eksperymentów; poza architekturą AInvoicer.
- Do osobnego ADR: przechowywanie XML/UPO w HANA zamiast płatnego Object Store.

## Current State

Instancja HANA free tier istnieje i odpowiada na SQL. CAP nadal działa na CF w identity-smoke (H2 w pamięci).
Brak `hdi-shared`, kontenera HDI, bindingu i redeployu.

## Next Steps

1. Właściciel: Costs and Usage — potwierdzić 0; przypomnienie o cotygodniowym starcie instancji.
2. Przypisać `hdi-shared` (SAP HANA Schemas & HDI Containers) po potwierdzeniu, że jest bezpłatny.
3. `cf login --sso` → agent odczytem potwierdza marketplace (`hana-cloud`, `hana`).
4. Zadanie integracyjne (po CAP-07D): pełny MTA z `ainvoicer-db`, testy HANA z ADR-0007, aktualizacja STATUS/ROADMAP/runbooka.
5. ADR: XML/UPO w HANA BLOB vs Object Store.
