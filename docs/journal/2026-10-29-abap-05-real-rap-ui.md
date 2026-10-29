---
title: "ABAP-05 + CAP-05.1 + UI5-05 — rzeczywisty RAP UI i regresja"
date: 2026-10-29
type: journal
status: zakonczony
tags: [ainvoicer, journal, abap-05, cap-05, sapui5, rap, iam, security]
---

# ABAP-05 + CAP-05.1 + UI5-05

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Przewodnik LIVE](../guides/RAP_UI_LIVE_CONNECTION.md) · [Patch/runbook](../patches/cap05-rap-ui/README.md)

## Date

2026-10-29, Europe/Warsaw.

## Branch / Initial State

Początek: `java-cap-ksef`, HEAD `9e4ac94513100bfeeeedc51c4ee4595c99e72510`, czyste drzewo. `git fetch origin` i porównanie java-cap-ksef...origin/java-cap-ksef: **0/0**. Historia potwierdziła merge CAP-05 (`9e4ac94`), poprawki `f543617` i fundament `2e0ee37`. Utworzono własny branch `feature/abap-05-real-rap-ui` z tej bazy. Nie scalano do java-cap-ksef/main.

Przeczytano instrukcje/skills ABAP-RAP, CAP Java, KSeF, STATUS/ROADMAP, dziennik i audyty CAP-05, ADR-0001/0005, runbook RAP UI i politykę WRITE. Raportowane wcześniejsze wyniki 173/57/13 oraz 4/9/6 potraktowano jako punkt odniesienia; poniższe wyniki pochodzą z bieżącego przebiegu.

## Goal

Największy możliwy postęp do rzeczywistego UI5 READ z H01, bez KSeF SEND, HTTP WRITE H01, zmian IAM, deploymentu i kosztownych zmian BTP. Lokalny hardening/regresja i przygotowanie konkretnego kroku ADT tam, gdzie MCP nie obsługuje V4 bindingu. Zachowanie AI/Scanner/Legacy i brak CAP proxy listy SAP.

## Implementation

### CAP-05.1

Potwierdzono konflikt: `application.yaml` miał retry-after-rejection=true, a ADR-0005 i fallback konstruktora OFF. YAML zmieniono na **false**. Włączenie pozostaje możliwe w zaufanej konfiguracji Spring; dotychczasowe testy jawnie włączają retry/max2 i nadal przechodzą. max-attempts obejmuje pierwszą próbę i musi należeć do **1..10**; błędna wartość blokuje start. Górny limit jest celową ochroną przed przypadkowo nadmiernym budżetem ponowień. Nie zmieniono schematu DB, stanów, rezerwacji ani istniejących rekordów.

Dodano test HTTP OData/H2 na rzeczywistym application.yaml, bez override polityki: pierwsza próba QUEUED, symulowane potwierdzone REJECTED/zamknięta sesja, ponowienie **409 KSEF_RESEND_NOT_ALLOWED**, nadal jedna operacja i zero wywołań transportów. Cztery przypadki błędnego budżetu (-1/0/11/MAX_INT) odrzucane przy konstrukcji.

Inspekcja KsefSendRequestHandler, KsefAcceptedWriteback, KsefSubmissionWorker, SapKsefResultWriteback/Recovery/Adapter oraz CDS potwierdza istniejące granice. Lokalna regresja sprawdza:

| Wymaganie | Wynik i dowód |
| --- | --- |
| równoległe requestSend → jedna rezerwacja; rollback/restart | PASS, KsefSendRequestTest / H2 |
| RAP ma numer lub CAP ma ACCEPTED przed writeback → 409 | PASS, KsefSendRequestTest |
| nieznane/FAILED/NEW SEND i RECONCILIATION_REQUIRED blokują | PASS, KsefSendRequestTest |
| obcy NIP lub CompanyCode → 403; odczyty zawężone oboma | PASS, testy OData/security |
| możliwy duplikat 440 nie jest zwykłym REJECTED | PASS, worker i requestSend |
| ACCEPTED commit przed enqueue; błąd enqueue nie cofa dowodu | PASS, KsefAcceptedWritebackTest |
| restart/sweeper odtwarza brakujące zlecenie, bez zagłodzenia | PASS, KsefAcceptedWritebackTest |
| UPO niezależne od niedostępnego SAP | PASS, worker / accepted writeback |
| VERSION_CONFLICT nie wywołuje SEND ani zmiany ExpectedVersion | PASS, read-only diagnose / writeback |
| fixture/UNVERIFIED bez SAP_RAP nie uruchamia zapisu SAP | PASS, testy writeback / provenance |
| błędy nie ujawniają konfiguracji SAP i sekretów | PASS, testy maskowania błędów OData/adaptera |

To testy lokalne z mockami zewnętrznych transportów, nie dowód HANA/multi-instance CF ani LIVE WRITE.

### ABAP-05 — własne wyniki H01

MCP odpowiada; **ABAP FS 2.13.3**, połączenie h01, pakiet ZAINVOICER, recordChanges=false, logging=false. Nie włączano logowania. Discovery wskazuje DDLS/DF, DCLS/DL, SRVD/SRV; nie wykonywano prób tworzenia ani nie wnioskowano z discovery o prawie zapisu. SRVB create nadal ogranicza bindingType do ODATA i kategorie do V2. Nie tworzono V2.

Zastano aktywne obiekty, których wcześniejsza dokumentacja nie uwzględniała:

| Nazwa | Typ | Stan zastany / weryfikacja | Zapis/aktywacja tej sesji |
| --- | --- | --- | --- |
| ZC_AINV_INV_H_UI | DDLS/DF | active; zwykły SELECT, #MANDATORY; ATC 0 | nie wykonano |
| ZC_AINV_INV_I_UI | DDLS/DF | active; własny _Parent; ATC 0 | nie wykonano |
| ZC_AINV_INV_REF_UI | DDLS/DF | active; własny _Parent; ATC 0 | nie wykonano |
| ZC_AINV_INV_H_UI | DCLS/DL | active; tuple NIP/spółka + ACTVT03; ATC 0 | nie wykonano |
| ZC_AINV_INV_I_UI | DCLS/DL | active; dziedziczenie warunku przez _Parent; ATC 0 | nie wykonano |
| ZC_AINV_INV_REF_UI | DCLS/DL | active; dziedziczenie warunku przez _Parent; ATC 0 | nie wykonano |
| ZUI_AINV_INV_H_O4 | SRVD/SRV | active; provider contracts odata_v4_ui; ATC 0 | nie wykonano |
| ZUI_AINV_INV_H_O4 | SRVB/SVB | resolve HTTP404 / NOT_FOUND | nie utworzono |

ATC używał **ABAP_CLOUD_DEVELOPMENT_DEFAULT**, bez exemptions/wyłączeń; 7/7 wyników z 0 ustaleń. Lista nieaktywnych obiektów UI: 0. Diagnostyka SRVD: 0, availableSources puste — nie raportujemy osobnego wykonanego Syntax Check. ABAP Unit nie uruchamiano: nie dodano kodu ABAP ani nie wykonywano biznesowych testów EML.

Odczytano także root CDS ZR_AINV_INV_H/I/REF, root DCL `where TRUE` i metadane SUSO Z_AINV_KSF (Cloud, ACTVT/ZAINV_NIP/ZAINV_BUK, aktywność 03 dostępna). DCL UI jest niezależny od otwartego root, OUTBOUND używa SellerNip, INBOUND BuyerNip. Reguła dzieci obejmuje również bezpośredni SELECT; jej efekt dla business userów pozostaje **NOT TESTED**. Nie sprawdzono ani nie nadano IAM roles/catalogs.

Aktywny SRVD ma `Invoices`, **InvoiceItems/InvoiceReferences**, nie wymagane Items/References. Preferujemy reuse siedmiu istniejących obiektów. Lokalny patch SRVD dostosowano do ich nazw/kontraktu V4 UI i docelowych aliasów dzieci; nie zastosowano go w SAP. Sześć wcześniejszych plików ZC_AINV_UI_* oznaczono jako historyczną alternatywę, której nie należy wdrażać równolegle. Nie zmieniano abap/src ani nie wykonywano eksportu/importu abapGit, ponieważ żaden obiekt H01 nie został zmieniony w tej sesji.

Web API ponownie rozstrzygnięto z rzeczywistego bindingu ZAPI_AINV_INV_H_O4: V4, wersja 0001, published=true; runtime path:

```text
/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/
```

Normalized metadata **HTTP200**, hash `fff24256b62e0d4a6d7fd916d52f8475ba9f55f66d9793defe823fbc173229bb`; query select/top20 + dwa expand top5: **HTTP200, 4 nagłówki, 9 pozycji, 6 referencji**, bez dalszych stron. Wszystkie UUID nagłówków poprawne; nie publikowano danych stron ani numerów faktur. To istniejący same-origin OAuth kontekst deweloperski MCP, nie test CAP Destination ani business usera UI. MCP query nie obsługuje filter/orderby/skip/count, więc nie zaliczono tych opcji LIVE.

### UI5-05

Zachowano named model rap, bezpośrednią listę/szczegóły z RAP i statusy CAP tylko dla UUID strony. Nie zmieniono Main.view/controller, manifestu ani domyślnego dataMode=mock; nie usunięto AI/Voice/Scanner/Add Invoice/history/Legacy.

Naprawiono dodatkowy przypadek fail closed: HTTP200 CAP z brakującą/nieprawidłową kolekcją lub count, niespójnym count, obcym UUID albo rekordem bez identyfikatora/typu/stanu/środowiska nie oznacza pustej historii. Dane RAP są widoczne, IntegrationAvailable=false, Send disabled. Testy wykonują prawdziwy V4 ODataModel przeciw mock RAP i fake CAP.

Dodano osobny szablon ui5-rap.yaml.example: `/rap-ui` + pathPrefix do rzeczywistego bindingu, CAP localhost:8080 i zachowana trasa Legacy /sap. Origin `.invalid` i placeholder ścieżki celowo nie są endpointem SAP; nie uruchamiano ich LIVE. Obsługę pathPrefix sprawdzono w zainstalowanych typach i implementacji proxy; build szablonu PASS. Kopia ui5-rap.local.yaml ignorowana. BAS destination `abap` oraz przyszły approuter/user propagation nie są zweryfikowane. Nie tworzono nowego serwera proxy ani credentiali w JS.

## Changed Files

- CAP: KsefSendRequestHandler.java, application.yaml, nowy KsefSendDefaultPolicyTest.java — polityka i testy.
- UI5: KsefDataAdapter.js, KsefRapMode.js — fail closed i regresja; ui5-rap.yaml.example i .gitignore — osobna konfiguracja przyszłego READ.
- Dokumentacja: ten dziennik, RAP_UI_LIVE_CONNECTION.md, STATUS.md, ROADMAP.md, README.md, SAPUI5_KSEF_COCKPIT.md, docs/patches/cap05-rap-ui/README.md oraz przygotowany plik SRVD. Żaden plik abap/src nie został zmieniony.

## Commands Executed / Tests & Results

Wersje: JDK Microsoft **25.0.4.1**, Maven **3.9.16**, Node **24.13.0**, npm **11.6.2**, UI5 **1.133.3**, CAP **5.2.0**, Boot **4.1.1**, Cloud SDK **5.33.0**, SDK MF **3.0.29**. `git ls-remote` potwierdził tag v3.0.29 → `3c3a899541074aa4bd944636b25659aef7d23da7`; SDK dostępny lokalnie, bez reinstalacji/aktualizacji.

| Polecenie / narzędzie | Wynik | Zakres |
| --- | --- | --- |
| git status / branch / rev-parse / fetch / log / rev-list | czysta baza, origin 0/0, CAP-05 scalony | Git READ |
| git switch -c feature/abap-05-real-rap-ui | PASS | własna gałąź |
| cap-java: mvn clean verify '-Dcds.cds.skipVersionCheck=true' | **178/178 PASS**, 0 failures/errors/skipped, BUILD SUCCESS | H2/OData/security/outbox + mock SAP/SDK, live tagi wykluczone |
| UI5: npm ci | PASS, lockfile bez zmian | 1135 pakietów; 82 zgłoszenia audit w toolchainie |
| UI5: npm run build | PASS | bez deploymentu |
| UI5: npm run serve-mock | serwer port8085 | wyłącznie lokalny mock |
| UI5: npm run test:unit | **59/59 PASS, 346/346 asercji** | mock RAP + fake CAP |
| UI5: npm run test:opa | **13/13 PASS, 80/80 asercji** | kokpit, AI/Scanner/Legacy; 2 oczekiwane TTS disabled console.error |
| UI5: npx ui5 build --config ui5-rap.yaml.example --clean-dest --dest dist | PASS | poprawność builda z szablonem; nie potwierdza tras LIVE |
| MCP: creation diagnostics, source/metadata, inactive list, ATC | wyniki opisane wyżej | aktywny H01 READ, bez modyfikacji |
| MCP: resolve/metadata/query Web API | **LIVE READ HTTP200, 4/9/6** | developer OAuth, nie UI/IAM |
| UI binding / READ dwóch business userów / UI5 LIVE | **BLOCKED** | brak SRVB i nadań do testu |
| HTTP WRITE H01 / KSeF SEND / IAM update / BTP deploy | **NOT EXECUTED** | zabronione w tym zleceniu |

Logi ignorowane w Git: cap-java/abap05-maven.log, sapui5/ainvoicer/abap05-npm-ci.log, abap05-ui-build.log, abap05-ui-template-build.log, abap05-unit.log, abap05-opa.log. Raport Maven podsumowano także z 26 plików surefire XML (178 testów). Nie kopiowano logów do dokumentacji. Przy późniejszych zmianach wyłącznie komentarza YAML/dokumentacji nie powtarzano pełnej regresji.

Źródła odniesienia sprawdzono: [CAP Persistence](https://cap.cloud.sap/docs/java/cqn-services/persistence-services), [Event Queues](https://cap.cloud.sap/docs/java/event-queues), [SAP tworzenie bindingu](https://help.sap.com/docs/abap-cloud/abap-development-tools-for-visual-studio-code/creating-service-binding), [przypięty tag SDK](https://github.com/CIRFMF/ksef-client-java/tree/v3.0.29). Odczyt strony SAP DCL/strony GitHub przez web zwrócił błąd pobrania; źródła aktywnego DCL/ATC H01 i tag Git potwierdzono niezależnie. Nie zmieniano wersji API ani bibliotek.

## Problems Encountered / Blockers

| Kod / komponent | Przyczyna i najmniejszy krok użytkownika | Inne prace |
| --- | --- | --- |
| **HTTP404 NOT_FOUND — UI SRVB** | brak bindingu pod nazwą ZUI_AINV_INV_H_O4; ADT: utworzyć OData V4 UI dla istniejącego SRVD, aktywować/publikować i przekazać Service URL | CAP/UI5 mock ukończone |
| **MCP schema V2-only — ograniczenie narzędzia, bez błędu SAP POST** | create nie dopuszcza V4; wykonać powyższy krok ADT, bez V2 workaround | tak |
| **IAM NOT VERIFIED — bez otrzymanego kodu HTTP** | brak dwóch principalów i zweryfikowanych ról; administrator zapewnia ACTVT03 + konkretne NIP/spółki i sesje A/B, potem macierz READ | tak, bez LIVE UI |
| **Routing/Destination NOT VERIFIED — bez kodu HTTP** | brak env destinations/VCAP_SERVICES, niepotwierdzone BAS abap/CF AINV_H01 i user propagation; uzupełnić oddzielną konfigurację po uzyskaniu URL i auth | tak |
| **Kontrakt aliasów — inspekcja** | SRVD ma InvoiceItems/InvoiceReferences; przejrzeć przygotowany patch do Items/References w ADT przed publikacją | nie blokuje lokalnego nav UI5 |

Narzędzie get_object_source nie znajdowało SRVD po nazwie, mimo że bezpośrednie ADT metadata/source potwierdziły jego istnienie. Nie tworzono duplikatu. Pierwsza próba ATC z krótkim adt:// URI zwróciła `Not an ABAP object`; po uzyskaniu workspace URI ATC zakończył się 0 ustaleń. get_sap_system_info zwrócił Unknown/N/A/null, więc nie ustalono release/client tym narzędziem; metadane odczytanych obiektów potwierdzają cloudDevelopment i H01. Nie wywodzono z tego nowych uprawnień.

`npm ci` raportuje 82 podatności zależności developerskich (6 low/23 moderate/48 high/5 critical); nie wykonano force upgrade toolchainu. Poprzedni audyt raportował 0 dla omit-dev; nie przypisujemy tego wyniku jako ponowionego pomiaru. Pełna aktualizacja zależności pozostaje osobnym zadaniem.

## Decisions Made / Current State

Zachowano ADR-0005; nie wprowadzono nowej architektury, migracji DB ani statusu biznesowego CAP. Reuse aktywnego UI jest prostszy niż wdrożenie równoległych patchy. **Lokalny sprint ukończony; główny cel LIVE UI pozostaje BLOCKED**, z konkretnym krokiem ADT/IAM. Przygotowano checklistę CAP-04D, bez włączania ainv.sap-write.enabled i bez HTTP ApplyKsefResult. Autoryzacja Git użytkownika obejmuje commit/push własnej gałęzi feature; wynik publikacji należy potwierdzić w końcowym raporcie i historii Git, nie zakładać go z samego przygotowania zmian.

## Next Steps

1. ADT/IAM: review aliasów istniejącego SRVD, utworzenie/publikacja V4 UI, konkretne zakresy dwóch użytkowników i pełna macierz READ z przewodnika.
2. Zweryfikować URI/tożsamość w osobnym routingu, odczyt UI5 LIVE i CAP statusów; do tego czasu mock i transporty SEND/WRITE OFF.
3. Osobno zatwierdzić jeden kontrolowany E2E UI5 → RAP → CAP requestSend → KSeF TEST → writeback → RAP, według [planu](../guides/RAP_UI_LIVE_CONNECTION.md#cap-04d--przygotowane-bez-write). Nie wykonano go w tym sprincie.
