package customer.cap_java.ksef.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * KSEF-STOR-04R — opt-in harness REAL HANA dla prywatnej tabeli {@code KsefStoredDocuments}.
 *
 * <p>Domyślnie wyłączony: wymaga właściwości {@code ainv.hana.stor04=true}, którą ustawia wyłącznie launcher R2
 * dla faz {@code stor04-*}, oraz bramki {@link Gate} wykonywanej PRZED kontekstem Spring/DataSource. Brak zgody,
 * zły commit, faza, środowisko, binding albo limit zapisów = wyjątek przed jakimkolwiek połączeniem z HANA.
 * Zwykłe {@code mvn verify} nie uruchamia sufiksu IT, a profil {@code hana-integration} obejmuje tylko
 * {@code HanaPersistenceIT}.
 *
 * <p>Fazy (każda osobna zgoda właściciela; brak łańcucha i domyślnej fazy):
 * <ul>
 *   <li>{@code catalog} — READ ONLY: 8 kolumn, typy, długości NVARCHAR, NOT NULL, PK, brak projekcji w modelu CAP;</li>
 *   <li>{@code roundtrip} — DML, ≤ 3 wiersze: dokładne bajty (BOM/CRLF), SHA-256, rozmiar, idempotencja bez UPDATE,
 *       ponowienie po potencjalnym commicie, ten sam SHA w innym scope, obcy scope/NIP, brak w BlobService,
 *       rollback wywołującego → trwały orphan → audyt → ponowne użycie;</li>
 *   <li>{@code contend} — DML, 1 wiersz: 8 równoległych INSERT tego samego PK → 1 CREATED + 7 ALREADY_EXISTS,
 *       rzeczywisty kod konfliktu CAP/HANA, odczyt po konflikcie w osobnej transakcji;</li>
 *   <li>{@code recover} — READ ONLY, nowa JVM (restart kontekstu CAP): te same bajty po ponownym starcie.</li>
 * </ul>
 * Treść dokumentów jest syntetyczna i deterministyczna z UUID runu (nie faktura KSeF). Adapter nie ma UPDATE/DELETE,
 * dlatego harness nie sprząta: wiersze runu pozostają jako oznaczone dane syntetyczne TEST (cleanup = osobna decyzja).
 * Uszkodzenie BLOB wymagałoby UPDATE, więc integralność vs błąd DB jest testowana offline (H2), a tu wyłącznie
 * rozróżnienie NOT_FOUND od niedostępności.
 */
@EnabledIfSystemProperty(named = "ainv.hana.stor04", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "ainv.deployment.identity-smoke=false", "ainv.deployment.hana-readiness=true", "spring.sql.init.mode=never",
    "ainv.ksef.enabled=false", "ainv.ksef.inbound.enabled=false", "ainv.sap-write.enabled=false",
    "ainv.sap-write.sweeper.enabled=false", "ainv.ksef.storage.provider=off",
    "cds.outbox.persistent.enabled=false", "cds.outbox.inMemory.enabled=false",
    "logging.level.root=ERROR", "logging.level.com.zaxxer.hikari=OFF", "spring.main.banner-mode=off"
})
@ActiveProfiles("cf")
class HanaKsefStoredDocumentsIT {
    static Plan plan;
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    @Autowired DataSource dataSource;

    /** Bramka przed kontekstem Spring: brak zgody = zero połączeń HANA i zero zapisów. */
    @DynamicPropertySource static void gate(DynamicPropertyRegistry properties) {
        plan = Gate.validate(System.getenv(), System.getProperty(Gate.BUILD_COMMIT_PROPERTY));
        properties.add("cds.environment.local.defaultEnvPath", () -> "hana-test-no-local-default-env.json");
    }

    @Test void stor04Phase() throws Exception {
        var hana = runtime.getEnvironment().getServiceBindings()
                .filter(b -> b.getServiceName().map("hana"::equals).orElse(false)).toList();
        assertEquals(1, hana.size());
        assertEquals(Gate.HDI, hana.getFirst().getName().orElse(""));
        assertEquals("hdi-shared", hana.getFirst().getServicePlan().orElse(""));
        var scenarios = new Scenarios(db, runtime, dataSource, plan, Product.HANA);
        scenarios.run();
        // Wyłącznie stałe znaczniki (launcher R2 filtruje wzorcem); bez danych, kluczy, referencji i credentiali.
        scenarios.markers.forEach(System.out::println);
        System.out.println("STOR04_HARNESS_PASS=" + plan.phase());
    }

    /** Zatwierdzony zakres jednego uruchomienia. {@code maxWrites} = górna granica nowych wierszy. */
    record Plan(String phase, String runId, String nip, String commit, int maxWrites, boolean readOnly) { }

    /** Fazy: zgoda, czy DML i liczba planowanych nowych wierszy. */
    enum Phase {
        CATALOG("catalog", true, 0), ROUNDTRIP("roundtrip", false, 3), CONTEND("contend", false, 1), RECOVER("recover", true, 0);
        final String id; final boolean readOnly; final int plannedRows;
        Phase(String id, boolean readOnly, int plannedRows) { this.id = id; this.readOnly = readOnly; this.plannedRows = plannedRows; }
        static Optional<Phase> of(String id) { return java.util.Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst(); }
    }

    /** Walidacja środowiska uruchomienia; czysta funkcja (testy offline bez zmiennych procesu). */
    static final class Gate {
        static final String READ_ONLY_CONSENT = "READ_ONLY_STOR04_CHECK_APPROVED_HDI";
        static final String WRITE_CONSENT = "WRITE_SYNTHETIC_STOR04_DOCUMENTS_APPROVED_HDI";
        static final String BUILD_COMMIT_PROPERTY = "ainv.stor04.build-commit";
        static final String HDI = "ainvoicer-db";
        static final int HARD_MAX_WRITES = 8;

        private Gate() { }

        static Plan validate(Map<String, String> env, String buildCommit) {
            if ("true".equalsIgnoreCase(env.get("GITHUB_ACTIONS")) || "true".equalsIgnoreCase(env.get("CI"))) {
                throw new IllegalStateException("STOR04_HARNESS_CI_FORBIDDEN");
            }
            String consent = env.get("AINV_STOR04_IT_CONSENT");
            if (!READ_ONLY_CONSENT.equals(consent) && !WRITE_CONSENT.equals(consent)) {
                throw new IllegalStateException("STOR04_HARNESS_EXPLICIT_OWNER_CONSENT_REQUIRED");
            }
            Phase phase = Phase.of(required(env, "AINV_STOR04_IT_PHASE"))
                    .orElseThrow(() -> new IllegalStateException("STOR04_HARNESS_PHASE_INVALID"));
            if (phase.readOnly != READ_ONLY_CONSENT.equals(consent)) {
                // Zgoda jest przypisana do rodzaju fazy: odczyt nie odblokowuje DML, zapis nie „rozszerza” odczytu.
                throw new IllegalStateException("STOR04_HARNESS_CONSENT_DOES_NOT_MATCH_PHASE");
            }
            String runId = required(env, "AINV_STOR04_IT_RUN_ID");
            if (!runId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !UUID.fromString(runId).toString().equals(runId)) {
                throw new IllegalStateException("STOR04_HARNESS_RUN_ID_INVALID");
            }
            String nip = required(env, "AINV_STOR04_IT_NIP");
            if (!KsefTaxpayerContext.isValidNip(nip)) { throw new IllegalStateException("STOR04_HARNESS_SYNTHETIC_NIP_REQUIRED"); }
            if (!"TEST".equals(env.get("AINV_STOR04_IT_ENVIRONMENT"))) { throw new IllegalStateException("STOR04_HARNESS_TEST_ONLY"); }
            if (!HDI.equals(env.get("AINV_STOR04_IT_HDI_NAME"))) { throw new IllegalStateException("STOR04_HARNESS_HDI_NOT_ALLOWED"); }
            String commit = required(env, "AINV_STOR04_IT_COMMIT");
            if (!commit.matches("[0-9a-f]{40}") || !commit.equals(buildCommit)) {
                throw new IllegalStateException("STOR04_HARNESS_COMMIT_NOT_APPROVED_BUILD");
            }
            String writes = required(env, "AINV_STOR04_IT_MAX_WRITES");
            if (!writes.matches("[0-" + HARD_MAX_WRITES + "]")) { throw new IllegalStateException("STOR04_HARNESS_MAX_WRITES_INVALID"); }
            int maxWrites = Integer.parseInt(writes);
            if (phase.readOnly ? maxWrites != 0 : maxWrites < phase.plannedRows) {
                throw new IllegalStateException("STOR04_HARNESS_MAX_WRITES_DOES_NOT_COVER_PHASE");
            }
            binding(env.get("VCAP_SERVICES"));
            return new Plan(phase.id, runId, nip, commit, maxWrites, phase.readOnly);
        }

        /** Dokładnie jeden zarządzany binding HDI {@code ainvoicer-db}; DBADMIN i niekompletne credentiale = odmowa. */
        static void binding(String vcap) {
            try {
                var services = new ObjectMapper().readTree(vcap == null ? "" : vcap);
                if (services == null || !services.isObject()) { throw new IllegalStateException("STOR04_HARNESS_BINDING_INVALID"); }
                int hana = 0;
                for (com.fasterxml.jackson.databind.JsonNode list : services) {
                    for (com.fasterxml.jackson.databind.JsonNode b : list) {
                        if (!"hana".equals(b.path("label").asText())) { continue; }
                        hana++;
                        var c = b.path("credentials");
                        if (!HDI.equals(b.path("name").asText()) || !"hdi-shared".equals(b.path("plan").asText())
                                || c.path("schema").asText().isBlank() || c.path("user").asText().isBlank()
                                || "DBADMIN".equalsIgnoreCase(c.path("user").asText().trim())) {
                            throw new IllegalStateException("STOR04_HARNESS_MANAGED_HDI_BINDING_REQUIRED");
                        }
                    }
                }
                if (hana != 1) { throw new IllegalStateException("STOR04_HARNESS_MANAGED_HDI_BINDING_REQUIRED"); }
            } catch (java.io.IOException error) {
                throw new IllegalStateException("STOR04_HARNESS_BINDING_INVALID");
            }
        }

        static String required(Map<String, String> env, String name) {
            String value = env.get(name);
            if (value == null || value.isBlank()) { throw new IllegalStateException("STOR04_HARNESS_REQUIRED_" + name); }
            return value;
        }
    }

    /** Oczekiwane nazwy typów JDBC zależnie od produktu; wyłącznie HDB jest dowodem LIVE. */
    enum Product {
        HANA("HDB", "NVARCHAR", "BIGINT", "TIMESTAMP", "BLOB"),
        H2_OFFLINE("H2", "CHARACTER VARYING", "BIGINT", "TIMESTAMP", "BINARY LARGE OBJECT");
        final String name, text, bigint, timestamp, blob;
        Product(String name, String text, String bigint, String timestamp, String blob) {
            this.name = name; this.text = text; this.bigint = bigint; this.timestamp = timestamp; this.blob = blob;
        }
    }

    /**
     * Dekorator SPI: zapis wyłącznie kluczy zaplanowanych dla fazy (≤ {@code maxWrites}); każdy inny klucz jest
     * odrzucany PRZED wywołaniem DB. Odczyt i listing bez zmian. Liczy wyniki create-only.
     */
    static final class WriteBudget implements ObjectStorageClient {
        final ObjectStorageClient delegate;
        final Set<String> allowed;
        final AtomicInteger created = new AtomicInteger(), existing = new AtomicInteger();

        WriteBudget(ObjectStorageClient delegate, Set<String> allowed, int maxWrites) {
            if (allowed.size() > maxWrites) { throw new IllegalStateException("STOR04_HARNESS_WRITE_BUDGET_EXCEEDED"); }
            this.delegate = delegate;
            this.allowed = Set.copyOf(allowed);
        }

        @Override public CreateOutcome createIfAbsent(String key, byte[] content, Map<String, String> metadata) {
            if (!allowed.contains(key)) { throw new IllegalStateException("STOR04_HARNESS_WRITE_NOT_PLANNED"); }
            var outcome = delegate.createIfAbsent(key, content, metadata);
            (outcome == CreateOutcome.CREATED ? created : existing).incrementAndGet();
            return outcome;
        }

        @Override public Optional<StoredObject> read(String key, int maxBytes) { return delegate.read(key, maxBytes); }

        @Override public ListPage list(String prefix, String marker, int maxResults) { return delegate.list(prefix, marker, maxResults); }
    }

    /** Scenariusze faz; współdzielone z regresją offline H2 (te same instrukcje, inny produkt DB). */
    static final class Scenarios {
        static final String ENTITY = HanaBlobObjectStorageClient.ENTITY;
        static final String TABLE = "CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS";
        static final String CONFLICT = "409006";
        static final DocumentScope INBOUND = DocumentScope.inbound(KsefEnvironment.TEST);
        final PersistenceService db;
        final CdsRuntime runtime;
        final DataSource dataSource;
        final Plan plan;
        final Product product;
        final ObjectStorageClient raw;
        final List<String> markers = new ArrayList<>();
        WriteBudget budget;

        Scenarios(PersistenceService db, CdsRuntime runtime, DataSource dataSource, Plan plan, Product product) {
            this.db = db; this.runtime = runtime; this.dataSource = dataSource; this.plan = plan; this.product = product;
            this.raw = new HanaBlobObjectStorageClient(db, runtime, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES,
                    HanaKsefDocumentStore.HARD_MAX_IN_FLIGHT_BYTES, Duration.ofSeconds(10), java.time.Clock.systemUTC());
        }

        void run() throws Exception {
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(product.name, connection.getMetaData().getDatabaseProductName());
            }
            switch (plan.phase()) {
                case "catalog" -> catalog();
                case "roundtrip" -> roundtrip();
                case "contend" -> contend();
                case "recover" -> recover();
                default -> fail("STOR04_HARNESS_PHASE_INVALID");
            }
        }

        // ------------------------------------------------------------------ dane syntetyczne i adresy

        /** Deterministyczny syntetyczny XML z BOM i CRLF; nie jest fakturą, zawiera UUID runu. */
        static byte[] document(String runId, String role) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
            out.writeBytes(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Stor04SyntheticProbe xmlns=\"urn:ainvoicer:stor04r:synthetic\">"
                    + "\r\n  <Run>" + runId + "</Run>\r\n  <Role>" + role + "</Role>\r\n  <Text>SYNTHETIC-NOT-AN-INVOICE ąęł€</Text>"
                    + "\r\n</Stor04SyntheticProbe>\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }

        byte[] roundtripDoc() { return document(plan.runId(), "ROUNDTRIP"); }
        byte[] orphanDoc() { return document(plan.runId(), "CALLER-ROLLBACK-ORPHAN"); }
        byte[] contendDoc() { return document(plan.runId(), "CONTEND"); }
        byte[] neverWrittenDoc() { return document(plan.runId(), "NEVER-WRITTEN"); }
        DocumentScope outboundScope() { return DocumentScope.outbound(KsefEnvironment.TEST, plan.nip()); }
        DocumentScope foreignScope() { return DocumentScope.outbound(KsefEnvironment.TEST, "1234567802".equals(plan.nip()) ? "7770002317" : "1234567802"); }

        static DocumentReference reference(DocumentScope scope, byte[] bytes) {
            return new DocumentReference(scope, KsefInvoiceSnapshotRegistry.sha256Hex(bytes));
        }

        static String key(DocumentScope scope, byte[] bytes) { return reference(scope, bytes).objectKey(HanaBlobObjectStorageClient.KEY_NAMESPACE); }

        /** Klucze, które dana faza może utworzyć — jedyne dopuszczone przez {@link WriteBudget}. */
        Set<String> plannedKeys() {
            return switch (plan.phase()) {
                case "roundtrip" -> Set.of(key(INBOUND, roundtripDoc()), key(outboundScope(), roundtripDoc()), key(INBOUND, orphanDoc()));
                case "contend" -> Set.of(key(INBOUND, contendDoc()));
                default -> Set.of();
            };
        }

        ContentAddressedDocumentStore store() {
            budget = new WriteBudget(raw, plannedKeys(), plan.maxWrites());
            return HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST),
                    HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES, HanaKsefDocumentStore.HARD_MAX_IN_FLIGHT_BYTES,
                    Duration.ofSeconds(10), java.time.Clock.systemUTC(), d -> Thread.sleep(d), client -> budget);
        }

        <T> T tx(Supplier<T> work) {
            return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                    runtime.changeSetContext().run(cs -> { return work.get(); }));
        }

        /** Wiersze o danym PK; tylko metadane, nigdy kolumna BLOB. */
        List<Map<String, Object>> rows(DocumentScope scope, byte[] bytes) {
            var a = new HanaBlobObjectStorageClient.Address(scope.environment().name(), scope.channel().name(),
                    scope.ownerSegment(), KsefInvoiceSnapshotRegistry.sha256Hex(bytes));
            return tx(() -> db.run(Select.from(ENTITY).columns("sizeBytes", "mimeType", "storedAt").where(a::where)).list()
                    .stream().<Map<String, Object>>map(LinkedHashMap::new).toList());
        }

        /** Fail-closed: run może zapisywać tylko raz; zajęty klucz = STOP bez kolejnych zapisów. */
        void requireUnused() {
            for (String key : plannedKeys()) {
                if (raw.read(key, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES).isPresent()) {
                    throw new IllegalStateException("STOR04_HARNESS_RUN_ALREADY_USED");
                }
            }
        }

        static String code(Runnable action) {
            try { action.run(); } catch (KsefFailure failure) { return failure.getMessage(); }
            return "NO_FAILURE";
        }

        // ------------------------------------------------------------------ fazy

        /** READ ONLY: katalog JDBC tabeli i brak projekcji w modelu CAP. Brak instancji magazynu = brak DML. */
        void catalog() throws SQLException {
            var expected = new LinkedHashMap<String, String>();
            expected.put("ENVIRONMENT", product.text + ":4"); expected.put("CHANNEL", product.text + ":16");
            expected.put("OWNERSEGMENT", product.text + ":32"); expected.put("SHA256", product.text + ":64");
            expected.put("MIMETYPE", product.text + ":100"); expected.put("SIZEBYTES", product.bigint);
            expected.put("STOREDAT", product.timestamp); expected.put("CONTENT", product.blob);
            var actual = new LinkedHashMap<String, String>();
            var keys = new ArrayList<String>();
            try (Connection connection = dataSource.getConnection()) {
                var md = connection.getMetaData();
                String schema = connection.getSchema();
                try (var columns = md.getColumns(null, schema, TABLE, null)) {
                    while (columns.next()) {
                        String type = columns.getString("TYPE_NAME");
                        assertEquals("NO", columns.getString("IS_NULLABLE"), columns.getString("COLUMN_NAME"));
                        actual.put(columns.getString("COLUMN_NAME"), type.equals(product.text) ? type + ":" + columns.getInt("COLUMN_SIZE") : type);
                    }
                }
                try (var pk = md.getPrimaryKeys(null, schema, TABLE)) {
                    var ordered = new java.util.TreeMap<Integer, String>();
                    while (pk.next()) { ordered.put(pk.getInt("KEY_SEQ"), pk.getString("COLUMN_NAME")); }
                    keys.addAll(ordered.values());
                }
            }
            assertEquals(sorted(expected), sorted(actual), "exactly 8 columns with exact type/length");
            assertEquals(List.of("ENVIRONMENT", "CHANNEL", "OWNERSEGMENT", "SHA256"), keys);
            // Żadna encja żadnego serwisu CAP nie czyta prywatnej tabeli (brak publicznego OData / $batch).
            runtime.getCdsModel().services().forEach(service -> runtime.getCdsModel().entities()
                    .filter(e -> e.getQualifiedName().startsWith(service.getQualifiedName() + "."))
                    .forEach(e -> assertFalse(e.query().map(Object::toString).orElse("").contains(ENTITY), e.getQualifiedName())));
            markers.add("STOR04_CATALOG=COLUMNS:8;PK:4;PROJECTIONS:0");
        }

        static Map<String, String> sorted(Map<String, String> map) { return new java.util.TreeMap<>(map); }

        /** DML ≤ 3 wiersze: pełny kontrakt bajtów, idempotencja, scope, izolacja LAB i orphan po rollback wywołującego. */
        void roundtrip() {
            var store = store();
            requireUnused();
            byte[] xml = roundtripDoc();
            KsefDocumentStore inbound = store.forScope(INBOUND), outbound = store.forScope(outboundScope());
            String expectedRef = "ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(xml);
            // INSERT oryginalnych bajtów → GET identycznych bajtów; BOM/CRLF zachowane, rozmiar i MIME z backendu.
            String ref = inbound.put(xml);
            assertEquals(expectedRef, ref);
            byte[] read = inbound.get(ref);
            assertArrayEquals(xml, read);
            assertEquals(KsefInvoiceSnapshotRegistry.sha256Hex(xml), KsefInvoiceSnapshotRegistry.sha256Hex(read));
            assertEquals((byte) 0xEF, read[0]);
            assertTrue(new String(read, StandardCharsets.UTF_8).contains("\r\n"));
            var row = rows(INBOUND, xml);
            assertEquals(1, row.size());
            assertEquals((long) xml.length, ((Number) row.getFirst().get("sizeBytes")).longValue());
            assertEquals("application/xml", row.getFirst().get("mimeType"));
            Object storedAt = row.getFirst().get("storedAt");
            // Ponowienie po potencjalnym commicie (np. timeout odpowiedzi): ta sama referencja, brak UPDATE i duplikatu.
            assertEquals(ref, inbound.put(xml));
            assertEquals(List.of(row.getFirst()), rows(INBOUND, xml));
            assertEquals(storedAt, rows(INBOUND, xml).getFirst().get("storedAt"));
            // Ten sam SHA w innym scope = osobny wiersz i osobna referencja; brak przenikania scope.
            String outboundRef = outbound.put(xml);
            assertNotEquals(ref, outboundRef);
            assertEquals(1, rows(outboundScope(), xml).size());
            assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> inbound.get(outboundRef)));
            assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> outbound.get(ref)));
            assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> store.forScope(foreignScope()).get(outboundRef)), "foreign NIP");
            assertEquals("KSEF_DOCUMENT_SCOPE_FORBIDDEN", code(() -> store.forScope(DocumentScope.inbound(KsefEnvironment.PROD))));
            // Hash/klucz nie jest uprawnieniem; brak wiersza = NOT_FOUND (nie błąd DB); błąd bez ujawnienia referencji.
            String guessed = "ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(neverWrittenDoc());
            assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound.get(guessed)));
            for (String bad : List.of(key(INBOUND, xml), KsefInvoiceSnapshotRegistry.sha256Hex(xml), ref + "x")) {
                String failure = code(() -> inbound.get(bad));
                assertEquals("KSEF_DOCUMENT_REFERENCE_INVALID", failure);
                assertFalse(failure.contains(KsefInvoiceSnapshotRegistry.sha256Hex(xml)));
            }
            // Dokument workera nie występuje w BlobService (projekcja LAB) ani w tabeli LAB — nawet dla privileged.
            String sha = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
            CqnService blob = runtime.getServiceCatalog().getService(CqnService.class, "BlobService");
            long visible = runtime.requestContext().privilegedUser().run((RequestContext ctx) ->
                    blob.run(Select.from("BlobService.Documents").columns("ID").where(d -> d.get("sha256").eq(sha))).rowCount());
            assertEquals(0, visible);
            assertEquals(0, tx(() -> db.run(Select.from("customer.cap_java.KsefDocuments").columns("ID")
                    .where(d -> d.get("sha256").eq(sha))).rowCount()));
            // Rollback wywołującego po trwałym put: wiersz zostaje (orphan), audyt go widzi, retry go ponownie używa.
            byte[] orphan = orphanDoc();
            String[] orphanRef = new String[1];
            assertThrows(IllegalStateException.class, () -> tx(() -> {
                orphanRef[0] = inbound.put(orphan);
                throw new IllegalStateException("STOR04_EXPECTED_CALLER_ROLLBACK");
            }));
            assertEquals(1, rows(INBOUND, orphan).size(), "own ChangeSet: durable regardless of caller transaction");
            var report = store.orphanReport(INBOUND, Set.of(ref), Duration.ZERO);
            assertTrue(report.orphanReferences().contains(orphanRef[0]));
            assertFalse(report.orphanReferences().contains(ref));
            assertEquals(orphanRef[0], inbound.put(orphan));
            assertEquals(1, rows(INBOUND, orphan).size());
            assertArrayEquals(orphan, inbound.get(orphanRef[0]));
            assertEquals(3, budget.created.get(), "exactly the planned rows were created");
            assertTrue(budget.created.get() <= plan.maxWrites());
            markers.add("STOR04_ROUNDTRIP=CREATED:" + budget.created.get() + ";ALREADY_EXISTS:" + budget.existing.get());
        }

        /** DML 1 wiersz: równoległe create-only tego samego PK, rzeczywisty kod konfliktu, odczyt po konflikcie. */
        void contend() throws Exception {
            var store = store();
            requireUnused();
            byte[] xml = contendDoc();
            String key = key(INBOUND, xml);
            var metadata = Map.of("ainvsha256", KsefInvoiceSnapshotRegistry.sha256Hex(xml),
                    "ainvlength", Integer.toString(xml.length), "ainvformat", ContentAddressedDocumentStore.FORMAT);
            int threads = 8;
            var barrier = new CyclicBarrier(threads);
            var pool = Executors.newFixedThreadPool(threads);
            try {
                var results = new ArrayList<Future<ObjectStorageClient.CreateOutcome>>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> { barrier.await(30, TimeUnit.SECONDS); return budget.createIfAbsent(key, xml, metadata); }));
                }
                for (var result : results) { result.get(120, TimeUnit.SECONDS); }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, budget.created.get(), "jedno CREATED");
            assertEquals(threads - 1, budget.existing.get(), "pozostałe ALREADY_EXISTS");
            assertEquals(1, rows(INBOUND, xml).size(), "jedna fizyczna pozycja");
            // Rzeczywisty konflikt PK w osobnej transakcji (wiersz już istnieje, więc nie może powstać nowy).
            var a = HanaBlobObjectStorageClient.parseKey(key);
            var duplicate = assertThrows(ServiceException.class, () -> tx(() -> db.run(Insert.into(ENTITY).entry(Map.of(
                    "environment", a.environment(), "channel", a.channel(), "ownerSegment", a.ownerSegment(), "sha256", a.sha256(),
                    "mimeType", "application/xml", "sizeBytes", (long) xml.length, "storedAt", java.time.Instant.now(),
                    "content", new java.io.ByteArrayInputStream(xml))))));
            assertEquals(CONFLICT, duplicate.getErrorStatus().getCodeString(), "CAP unique conflict mapping");
            for (Throwable cause = duplicate; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                    markers.add("HANA_UNIQUE_SQLSTATE=" + sql.getSQLState() + ";VENDOR_CODE=" + sql.getErrorCode());
                    break;
                }
            }
            // Read-after-conflict w nowej transakcji: zwycięski wiersz ma dokładne bajty.
            assertArrayEquals(xml, store.forScope(INBOUND).get(reference(INBOUND, xml).value()));
            assertEquals(1, rows(INBOUND, xml).size());
            markers.add("STOR04_CONTEND=CREATED:" + budget.created.get() + ";ALREADY_EXISTS:" + budget.existing.get());
        }

        /** READ ONLY w nowej JVM: dokładne bajty wszystkich dokumentów runu po restarcie kontekstu CAP. */
        void recover() {
            var store = store();
            KsefDocumentStore inbound = store.forScope(INBOUND), outbound = store.forScope(outboundScope());
            for (byte[] xml : List.of(roundtripDoc(), orphanDoc(), contendDoc())) {
                assertArrayEquals(xml, inbound.get(reference(INBOUND, xml).value()));
                assertEquals(1, rows(INBOUND, xml).size());
            }
            assertArrayEquals(roundtripDoc(), outbound.get(reference(outboundScope(), roundtripDoc()).value()));
            assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound.get(reference(INBOUND, neverWrittenDoc()).value())));
            assertEquals(0, budget.created.get() + budget.existing.get(), "read-only phase never writes");
            markers.add("STOR04_RECOVER=DOCUMENTS:4");
        }
    }
}
