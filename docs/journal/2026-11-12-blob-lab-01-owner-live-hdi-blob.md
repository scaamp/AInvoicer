---
title: "BLOB-LAB-01 — owner-led: CDS LargeBinary → HDI → fizyczny BLOB w HANA Cloud"
date: 2026-11-12
type: journal
status: owner-reported-live-schema-and-read-verified
tags: [ainvoicer, blob-lab-01, cap-java, cds, hana-cloud, hdi, mtar, owner-led]
---

# BLOB-LAB-01 — wynik sesji właściciela, 12.11.2026

[Zasady laboratorium](../guides/BLOB_LAB_01_OWNER_LED_HDI.md) ·
[Runbook wdrożeniowy CAP-HANA-01](../guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) ·
[Dokumentacja projektu](../README.md)

## Cel i zakres

Właściciel **samodzielnie** rozwinął istniejący projekt AInvoicer (CAP Java) o encję dokumentów z kolumną binarną i przeprowadził pełną ścieżkę **CDS → wygenerowany artefakt HANA → HDI Deployer → HANA Cloud**. Praca była prowadzona krok po kroku na Windows / PowerShell, z asystentem w roli mentora i diagnosty.

Wybrano **wariant B** z przewodnika LAB: istniejąca aplikacja, istniejąca instancja HANA Cloud Free Tier `ainvoicer-hana-dev` i istniejący kontener HDI `ainvoicer-db`. **Nie tworzono nowej instancji ani nowego kontenera** na potrzeby laboratorium.

**Klasyfikacja dowodów:** wyniki LIVE niżej pochodzą z logów CLI i zrzutów ekranu SQL Console przekazanych przez właściciela podczas rozmowy (**OWNER-REPORTED / OWNER-SCREENSHOT-VERIFIED**), nie z niezależnej sesji operatora. Nie są dowodem kompletnego testu persystencji plików.

## 1. Model i mapowanie do HANA — potwierdzone

- Nowy, **lokalny i nieśledzony przez Git** plik: `cap-java/db/documents.cds`.
- Namespace: `customer.cap_java`; encja: `KsefDocuments`, z aspektami `cuid` i `managed`.
- Pola własne: `fileName: String(255)`, `mimeType: String(100)`, `content: LargeBinary`, `sha256: String(64)`.
- `cds compile db/documents.cds` poprawnie wygenerował model CSN.
- `cds build --for hana --production` wygenerował artefakty w `cap-java/db/src/gen/` (nie `gen/db/`).
- Wygenerowany plik: `src/gen/customer.cap_java.KsefDocuments.hdbtable`.

Zweryfikowana definicja DDL z artefaktu (fragment):

```sql
COLUMN TABLE customer_cap_java_KsefDocuments (
  ID NVARCHAR(36) NOT NULL,
  createdAt TIMESTAMP,
  createdBy NVARCHAR(255),
  modifiedAt TIMESTAMP,
  modifiedBy NVARCHAR(255),
  fileName NVARCHAR(255),
  "MIMETYPE" NVARCHAR(100),
  content BLOB,
  sha256 NVARCHAR(64),
  PRIMARY KEY(ID)
)
```

**Wniosek:** w SAP CAP CDS typ `LargeBinary` mapuje się w tym buildzie na relacyjną kolumnę HANA **`BLOB`**. Nazwa kolumny MIME w wygenerowanym SQL to `"MIMETYPE"` — przy ręcznym SQL trzeba respektować quoting/case.

> **Ważna luka w Git:** `documents.cds` był podczas sesji `?? cap-java/db/documents.cds`, a `.env.example` miał lokalną modyfikację. Samo wdrożenie z lokalnego dirty working tree nie oznacza, że źródłowy model jest już w GitHub `main`. Przed następnym buildem na innym checkoutcie należy osobno zatwierdzić model i świadomie zsynchronizować repo, bez `git reset --hard` i bez `git add .`.

## 2. Kompilacja, testy i pakowanie MTA — potwierdzone lokalnie

Wykonane przez właściciela polecenia:

```powershell
npm ci --prefix deploy
npm test --prefix deploy
npm ci --prefix cap-java
npm run build:btp:smoke --prefix deploy
npm run build:btp:hana --prefix deploy
npm run verify:hana --prefix deploy
```

Zarejestrowane wyniki:

| Krok | Wynik |
| --- | --- |
| `npm ci --prefix deploy` | 121 pakietów, instalacja OK, audit 0 |
| `npm test --prefix deploy` | 33 testy: 32 PASS, 1 SKIPPED (brak Go lokalnie) |
| Maven `verify` w smoke build | 468 testów, 0 failures, 0 errors, 6 skipped; BUILD SUCCESS |
| Pakietowy `test:cf-runtime` | 10/10 PASS |
| Testy UI5 `test:cf-build` | 13/13 PASS |
| Testy AppRouter `test:config` | 10/10 PASS |
| `ainvoicer_0.7.2.mtar` | Zweryfikowany **smoke**: 2 moduły, **bez HDI** |
| `ainvoicer_0.7.3.mtar` | `HANA MTAR validated OFFLINE`, 3 moduły, w tym HDI |
| `npm run verify:hana --prefix deploy` | `Undeployed HANA release evidence PASS` |

Konfiguracja kontrolowanego buildu znajduje się w `deploy/scripts/hana-readiness.mjs`. Wariant `0.7.3` obejmuje istniejące usługi `ainvoicer-auth`, `ainvoicer-destination`, `ainvoicer-db`, **bez provisioningu nowego HDI/HANA** i z wyłączonymi efektami zewnętrznymi KSeF / SAP WRITE / INBOUND / outbox.

Właściciel **otworzył rzeczywisty MTAR jako ZIP** i sprawdził zawartość `ainvoicer-db-deployer/data.zip`. Wynik:

```text
FOUND: src/gen/customer.cap_java.KsefDocuments.hdbtable
...
content BLOB,
```

To dowodzi obecności tej konkretnej tabeli w **artefakcie, który został przygotowany do wdrożenia**, a nie tylko w lokalnym `src/gen`.

### Incydenty podczas buildu i rozwiązania

1. Maven `clean` nie mógł usunąć `srv/target/cap-java-exec.jar` na Windows. Identyfikacja `Get-CimInstance Win32_Process` wykazała lokalny CAP Java (`java -jar srv/target/cap-java-exec.jar`, PID 37384). Właściciel zakończył wyłącznie ten proces; pozostawił Java Language Server w VS Code i inny proces Java.
2. Kolejne uruchomienie Maven zakończyło `cds-maven-plugin:npm (cds.npm-ci)` z kodem `-4048`; osobne `npm ci --prefix cap-java` zakończyło się sukcesem, po czym pełny build przeszedł. Konkretny blokowany plik w drugim błędzie nie został ustalony.
3. `npm audit` uruchomione z katalogu root zwróciło `ENOLOCK` — wymagany jest właściwy podprojekt (`--prefix cap-java`).
4. Audit `cap-java` zgłosił 2 HIGH związane z zależnością `@sap/cds-dk` → `body-parser`; proponowane `npm audit fix --force` cofałoby major `@sap/cds-dk` 10 → 9. **Nie wykonano force fix**. Nie ma potwierdzonego wyniku `npm audit --omit=dev --prefix cap-java`.
5. Instalacja zależności UI5 zgłosiła **74 podatności** (2 low, 15 moderate, 54 high, 3 critical). Build przeszedł, ale ryzyko wymaga osobnego triage przed ekspozycją nowej funkcji. Nie wykonano automatycznych aktualizacji.
6. Jednowierszowy skrypt `node -e` z pojedynczymi cudzysłowami na zewnątrz nie zadziałał w PowerShell; po użyciu podwójnych na zewnątrz inspekcja archiwum zakończyła się `FOUND`.

## 3. Deployment LIVE do istniejącego HDI — potwierdzony przez właściciela

Preflight `cf target`, `cf services`, `cf apps`, `cf mta-ops`:

- Target: CF API EU20, organizacja właściciela, przestrzeń `dev`.
- Istniejące `ainvoicer-hana-dev` (`hana-free`) i `ainvoicer-db` (`hdi-shared`).
- `ainvoicer-db` związany z `ainvoicer-db-deployer` i `ainvoicer-srv`.
- CAP i AppRouter `started`, deployer `stopped` (oczekiwany stan modułu zadaniowego).
- **Brak aktywnych operacji MTA** przed deploymentem.

Właściciel uruchomił kontrolowany deployment (rozszerzenie na istniejące usługi, bez automatycznych retry):

```powershell
Remove-Item Env:DEBUG, Env:CF_TRACE -ErrorAction SilentlyContinue
cf deploy .\deploy\build\ainvoicer_0.7.3.mtar `
  -e .\deploy\btp-dev-eu20-hana-first.mtaext `
  --retries 0
```

Końcówka logu: `Application "ainvoicer-approuter" started`, `Process finished.`; identyfikator operacji MTA zaczynał się od `5308ae31`.

Po deploymentcie:

```text
cf tasks ainvoicer-db-deployer
3   deploy   SUCCEEDED   Thu, 12 Nov 2026 18:11:25 UTC   npm start

cf app ainvoicer-srv
requested state: started
instances: 1/1
stack: cflinuxfs4
sap_java_buildpack_jakarta: 2.77.0
ready: true
```

**Interpretacja:** potwierdzono wykonanie nowego zadania HDI #3 i start CAP Java. Poniższy test katalogowy i `SELECT` są dodatkowym, niezależnym dowodem faktycznego utworzenia i udostępnienia tabeli w HANA.

## 4. SQL LIVE w SAP HANA Cloud Central — potwierdzony screenshotami właściciela

### 4.1 Fizyczna tabela i typ kolumny

Początkowo SQL Console był otwarty na instancji `ainvoicer-hana-dev` z `CURRENT_SCHEMA = DBADMIN`.

```sql
SELECT SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, DATA_TYPE_NAME
FROM SYS.TABLE_COLUMNS
WHERE UPPER(TABLE_NAME) = 'CUSTOMER_CAP_JAVA_KSEFDOCUMENTS'
  AND UPPER(COLUMN_NAME) = 'CONTENT';
```

**Wynik: jeden wiersz; `COLUMN_NAME = CONTENT`, `DATA_TYPE_NAME = BLOB`.** Tabela znajduje się w schemacie technicznym HDI.

Bezpośredni `SELECT COUNT(*)` jako `DBADMIN` zwrócił `[258] insufficient privilege`. Jest to granica uprawnień HDI, a nie brak tabeli; nie wykonano żadnego `GRANT`.

### 4.2 Poprawny runtime user kontenera HDI

Ścieżka w aktualnym UI:

1. **HANA Cloud Central → Schemas & HDI Containers**.
2. Przy istniejącym `ainvoicer-db` → menu `...`.
3. **Open HDI Container in SQL Console** (nie `Open Database Instance in SQL Console`).

```sql
SELECT CURRENT_USER, CURRENT_SCHEMA FROM DUMMY;

SELECT COUNT(*) AS DOCUMENT_COUNT
FROM "CUSTOMER_CAP_JAVA_KSEFDOCUMENTS";
```

**Wynik:** `CURRENT_USER` zakończony `_RT`, `CURRENT_SCHEMA` równy schematowi HDI i **`DOCUMENT_COUNT = 0`**. Tym sposobem odczyt tabeli działał w kontekście kontenera, bez poszerzania uprawnień `DBADMIN`.

## 5. Stan końcowy i granice dowodów

| Kryterium BLOB-LAB-01 | Stan na 12.11.2026 |
| --- | --- |
| Własnoręczny model CDS / kompilacja | **OWNER-REPORTED PASS** |
| `LargeBinary` → `.hdbtable` → `BLOB` | **OWNER-REPORTED PASS** |
| MTAR HANA z konkretną tabelą | **OWNER-REPORTED PASS** |
| Deployment do istniejącego HDI; task #3 | **OWNER-REPORTED LIVE PASS** |
| Katalog HANA: tabela i `CONTENT BLOB` | **OWNER-SCREENSHOT-VERIFIED** |
| `SELECT COUNT(*) = 0` jako HDI `_RT` | **OWNER-SCREENSHOT-VERIFIED** |
| `documents.cds` zatwierdzony w Git | **NIE** — lokalny untracked |
| `@Core.MediaType` / `@Core.IsMediaType` w źródle | **NIEPOTWIERDZONE** — tylko zalecone po ostatnim teście |
| Dedykowany zabezpieczony serwis CAP OData V4 | **NIE ZAIMPLEMENTOWANO W TEJ SESJI** |
| Upload/download XML/PDF, INSERT/SELECT BLOB przez CAP | **NIE WYKONANO** |
| Porównanie SHA-256 pliku, odczyt po restarcie | **NIE WYKONANO** |
| Autoryzacja per dokument / security sign-off | **PENDING** |

Nie wysyłano faktur do KSeF, nie włączano INBOUND/SAP WRITE ani workerów, nie tworzono testowych dokumentów i **nie wykonano DML** podczas tej sesji.

## 6. Zalecana dalsza sekwencja — właściciel wykonuje sam

1. Zweryfikować lokalny `cap-java/db/documents.cds`, zachować dokładny model, bezpiecznie wprowadzić go do Git w osobnym, świadomym commicie. Repo `main` nie zawiera jeszcze tego pliku.
2. Dodać w CDS odpowiednie semantyki mediów (`@Core.IsMediaType`, `@Core.MediaType`, `@Core.ContentDisposition.Filename`) **dopiero po sprawdzeniu aktualnej wersji CAP** i ponownie skompilować model; propozycja nie oznacza wykonanej zmiany.
3. Zdefiniować minimalną **zabezpieczoną** usługę/projekcję OData V4 dla syntetycznych dokumentów, świadomie zaprojektować CREATE/READ oraz autoryzację per dokument; nie wystawiać surowej tabeli publicznie.
4. Testować mały syntetyczny XML/PDF przez CAP Java → HDI runtime → HANA, potem pobrać i porównać bajty/SHA-256.
5. Osobno uzgodnić test restartu i trwałości. Nie testować na prawdziwych fakturach.
6. Przeanalizować podatności toolchainu UI5 i wynik audytu produkcyjnych zależności przed publikacją nowej funkcji.

**To jest postęp edukacyjnego BLOB-LAB-01, nie akceptacja produkcyjnego storage KSeF ani zgoda na autonomiczne wykonanie kolejnych kroków przez agentów.** Zasady owner-led z [przewodnika](../guides/BLOB_LAB_01_OWNER_LED_HDI.md) pozostają w mocy.
