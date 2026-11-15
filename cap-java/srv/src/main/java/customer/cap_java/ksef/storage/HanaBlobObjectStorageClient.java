package customer.cap_java.ksef.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sap.cds.Row;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Predicate;
import com.sap.cds.ql.StructuredType;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * KSEF-STOR-04: protokół {@link ObjectStorageClient} nad prywatną tabelą CAP {@code KsefStoredDocuments}
 * (HANA {@code BLOB}, lokalnie H2) wyłącznie przez CAP {@link PersistenceService} i CQN — bez JDBC, JPA,
 * HTTP CAP → CAP i bez żadnej projekcji OData.
 *
 * <p>Reguły KSeF (scope, referencja {@code ksefdoc:v1}, SHA-256, retry) egzekwuje
 * {@link ContentAddressedDocumentStore}. Ta klasa tłumaczy zwalidowany klucz obiektu na złożony klucz główny
 * {@code (environment, channel, ownerSegment, sha256)}:
 * <ul>
 *   <li>PK = logiczny adres dokumentu i jednocześnie fizyczne UNIQUE — równoległe INSERT tego samego dokumentu
 *       kończą się jednym wierszem i naruszeniem PK ({@code 409006}) u pozostałych; nie ma losowego ID;</li>
 *   <li>{@code sha256} w PK jest zawsze liczony tutaj z zapisywanych bajtów (niezgodność z kluczem = odmowa);</li>
 *   <li>tabela nie ma kolumn {@code createdBy}/{@code modifiedBy} — izolacja nie zależy od nazwy principala;</li>
 *   <li>klasa nie wykonuje UPDATE ani DELETE.</li>
 * </ul>
 *
 * <p>Każda operacja DB to osobny RequestContext (system user) i osobny ChangeSet: zapis jest trwały niezależnie
 * od transakcji wywołującego (jak PUT w Object Store), a referencję w CAP finalizuje dopiero wywołujący.
 *
 * <p>Pamięć: BLOB jest materializowany do {@code byte[]} (SPI). Łączna liczba bajtów dokumentów jednocześnie
 * zapisywanych/czytanych przez instancję jest ograniczona semaforem ({@code maxInFlightBytes}); brak miejsca w
 * zadanym czasie = {@code UNAVAILABLE} (bezpieczne ponowienie). Odczyt najpierw sprawdza {@code sizeBytes},
 * dopiero potem rezerwuje pamięć i czyta strumień BLOB z twardym ograniczeniem długości.
 */
final class HanaBlobObjectStorageClient implements ObjectStorageClient {
    static final String ENTITY = "customer.cap_java.KsefStoredDocuments";
    static final String MIME_TYPE = "application/xml";
    /** Stała przestrzeń kluczy SPI; nie jest utrwalana — wdrożenia izoluje osobny kontener HDI. */
    static final String KEY_NAMESPACE = "hana-ksef";
    private static final String UNIQUE_VIOLATION = "409006"; // CAP 5.2 UNIQUE_CONSTRAINT_VIOLATED (jak InboundPersistence)
    private static final String SEGMENTS = "/v1/(test|demo|prod)/(outbound|inbound)/([0-9]{10}|shared)/";
    private static final Pattern KEY = Pattern.compile(Pattern.quote(KEY_NAMESPACE) + SEGMENTS + "([a-f0-9]{64})\\.xml");
    private static final Pattern PARTITION = Pattern.compile(Pattern.quote(KEY_NAMESPACE) + SEGMENTS);

    private final PersistenceService db;
    private final CdsRuntime runtime;
    private final int maxBytes;
    private final Clock clock;
    private final Semaphore memory;
    private final int memoryPermits;
    private final Duration memoryWait;

    HanaBlobObjectStorageClient(PersistenceService db, CdsRuntime runtime, int maxBytes, long maxInFlightBytes,
            Duration memoryWait, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.runtime = Objects.requireNonNull(runtime);
        this.clock = Objects.requireNonNull(clock);
        this.memoryWait = Objects.requireNonNull(memoryWait);
        if (maxBytes < 1 || maxInFlightBytes < maxBytes || memoryWait.isNegative()) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_LIMIT_INVALID");
        }
        this.maxBytes = maxBytes;
        this.memoryPermits = Math.toIntExact(permits(maxInFlightBytes));
        this.memory = new Semaphore(memoryPermits, true);
    }

    /** Wiersz tabeli wyliczony ze zwalidowanego klucza; wartości w formacie kolumn (np. {@code TEST}, {@code -}). */
    record Address(String environment, String channel, String ownerSegment, String sha256) {
        Predicate where(StructuredType<?> d) {
            return d.get("environment").eq(environment).and(d.get("channel").eq(channel))
                    .and(d.get("ownerSegment").eq(ownerSegment)).and(d.get("sha256").eq(sha256));
        }
    }

    /**
     * Create-only INSERT pod złożonym PK. Naruszenie PK oznacza wyścig lub ponowienie: istniejący wiersz musi
     * mieć ten sam rozmiar i typ, inaczej {@code CORRUPTED}; pełną kontrolę bajtów i SHA-256 wykonuje
     * {@link ContentAddressedDocumentStore} odczytem kontrolnym. Inne błędy DB = {@code UNAVAILABLE}
     * (INSERT jest atomowy, więc ponowienie jest bezpieczne).
     */
    @Override
    public CreateOutcome createIfAbsent(String key, byte[] content, Map<String, String> metadata) {
        Address address = parseKey(key);
        if (content == null || content.length == 0 || content.length > maxBytes) {
            throw new ObjectStorageException(ObjectStorageException.Category.TOO_LARGE, "HANA_DOCUMENT_SIZE_INVALID");
        }
        return withMemory(content.length, () -> {
            byte[] bytes = content.clone();
            // Backend liczy hash z bajtów, które faktycznie trafią do BLOB; klucz z innym hashem jest odrzucany.
            if (!address.sha256().equals(KsefInvoiceSnapshotRegistry.sha256Hex(bytes))) {
                throw new ObjectStorageException(ObjectStorageException.Category.REJECTED, "HANA_DOCUMENT_KEY_CONTENT_MISMATCH");
            }
            if (tryInsert(address, bytes)) { return CreateOutcome.CREATED; }
            var existing = inTransaction(() -> db.run(Select.from(ENTITY).columns("sizeBytes", "mimeType")
                    .where(address::where)).first());
            // Wiersz zniknął po konflikcie PK (adapter nie usuwa) — traktujemy jako chwilowy stan, nie zgadujemy.
            if (existing.isEmpty()) { throw unavailable(); }
            if (!MIME_TYPE.equals(existing.get().get("mimeType")) || size(existing.get()) != bytes.length) {
                throw new ObjectStorageException(ObjectStorageException.Category.CORRUPTED, "HANA_DOCUMENT_CORRUPTED");
            }
            return CreateOutcome.ALREADY_EXISTS;
        });
    }

    /**
     * Odczyt po PK: (1) metadane i limit bez czytania BLOB, (2) rezerwacja pamięci, (3) strumień BLOB czytany
     * najwyżej do {@code sizeBytes + 1} bajtów w osobnej transakcji. Pusty wynik = potwierdzony brak dokumentu.
     */
    @Override
    public Optional<StoredObject> read(String key, int limit) {
        Address address = parseKey(key);
        int effective = Math.min(limit, maxBytes);
        var header = inTransaction(() -> db.run(Select.from(ENTITY).columns("sizeBytes", "mimeType")
                .where(address::where)).first());
        if (header.isEmpty()) { return Optional.empty(); }
        long declared = size(header.get());
        if (!MIME_TYPE.equals(header.get().get("mimeType")) || declared < 1) {
            throw new ObjectStorageException(ObjectStorageException.Category.CORRUPTED, "HANA_DOCUMENT_CORRUPTED");
        }
        if (declared > effective) {
            throw new ObjectStorageException(ObjectStorageException.Category.TOO_LARGE, "HANA_DOCUMENT_SIZE_INVALID");
        }
        int length = (int) declared;
        return withMemory(length, () -> inTransaction(() -> {
            var row = db.run(Select.from(ENTITY).columns("content").where(address::where)).first();
            if (row.isEmpty()) { return Optional.<StoredObject>empty(); }
            byte[] bytes = readContent(row.get().get("content"), length);
            // Metadane dla ContentAddressed: deklarowana długość i hash z PK; obie wartości są tam weryfikowane.
            return Optional.of(new StoredObject(bytes, Map.of("ainvsha256", address.sha256(),
                    "ainvlength", Integer.toString(length))));
        }));
    }

    /** Listing jednej partycji dla audytu orphan, stronicowany po {@code sha256}; nie czyta BLOB. */
    @Override
    public ListPage list(String prefix, String marker, int maxResults) {
        Matcher partition = prefix == null ? null : PARTITION.matcher(prefix);
        Address markerAddress = marker == null ? null : parseKeyOrNull(marker);
        if (partition == null || !partition.matches() || maxResults < 1 || maxResults > 5000
                || marker != null && (markerAddress == null || !marker.startsWith(prefix))) {
            throw new ObjectStorageException(ObjectStorageException.Category.REJECTED, "HANA_DOCUMENT_LIST_INVALID");
        }
        Address scope = address(partition, null);
        return inTransaction(() -> {
            var query = Select.from(ENTITY).columns("sha256", "sizeBytes", "storedAt")
                    .where(d -> {
                        Predicate filter = d.get("environment").eq(scope.environment())
                                .and(d.get("channel").eq(scope.channel()))
                                .and(d.get("ownerSegment").eq(scope.ownerSegment()));
                        return markerAddress == null ? filter : filter.and(d.get("sha256").gt(markerAddress.sha256()));
                    })
                    .orderBy(d -> d.get("sha256").asc())
                    .limit(maxResults + 1);
            var objects = new ArrayList<ListedObject>();
            boolean more = false;
            for (Row row : db.run(query).list()) {
                if (objects.size() == maxResults) { more = true; break; }
                objects.add(new ListedObject(prefix + row.get("sha256") + ".xml", size(row), instant(row.get("storedAt"))));
            }
            return new ListPage(List.copyOf(objects), more ? objects.getLast().key() : null);
        });
    }

    private boolean tryInsert(Address address, byte[] bytes) {
        try {
            inTransaction(() -> db.run(Insert.into(ENTITY).entry(Map.of(
                    "environment", address.environment(), "channel", address.channel(),
                    "ownerSegment", address.ownerSegment(), "sha256", address.sha256(),
                    "mimeType", MIME_TYPE, "sizeBytes", (long) bytes.length, "storedAt", clock.instant(),
                    "content", new ByteArrayInputStream(bytes)))));
            return true;
        } catch (ServiceException error) {
            if (UNIQUE_VIOLATION.equals(error.getErrorStatus().getCodeString())) { return false; }
            throw unavailable();
        }
    }

    /** Ścisłe parsowanie klucza; kombinacje niedozwolone przez {@link DocumentScope} (np. INBOUND z NIP) = odmowa. */
    static Address parseKey(String key) {
        Address address = parseKeyOrNull(key);
        if (address == null) {
            throw new ObjectStorageException(ObjectStorageException.Category.REJECTED, "HANA_DOCUMENT_KEY_INVALID");
        }
        return address;
    }

    private static Address parseKeyOrNull(String key) {
        if (key == null) { return null; }
        Matcher matcher = KEY.matcher(key);
        return matcher.matches() ? address(matcher, matcher.group(4)) : null;
    }

    private static Address address(Matcher matcher, String sha256) {
        var environment = KsefEnvironment.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        var channel = DocumentScope.Channel.valueOf(matcher.group(2).toUpperCase(Locale.ROOT));
        String owner = "shared".equals(matcher.group(3)) ? null : matcher.group(3);
        try {
            var scope = new DocumentScope(environment, channel, owner);
            return new Address(environment.name(), channel.name(), scope.ownerSegment(), sha256);
        } catch (IllegalArgumentException invalidCombination) {
            throw new ObjectStorageException(ObjectStorageException.Category.REJECTED, "HANA_DOCUMENT_KEY_INVALID");
        }
    }

    /**
     * Rezerwacja budżetu pamięci na czas operacji. Czekanie poza transakcją DB (nie blokuje połączenia z puli);
     * przekroczenie {@code memoryWait} lub przerwanie wątku = {@code UNAVAILABLE}.
     */
    private <T> T withMemory(long bytes, Supplier<T> work) {
        int needed = (int) Math.min(permits(bytes), memoryPermits);
        try {
            if (!memory.tryAcquire(needed, memoryWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ObjectStorageException(ObjectStorageException.Category.UNAVAILABLE, "HANA_DOCUMENT_STORE_BUSY");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        try {
            return work.get();
        } finally {
            memory.release(needed);
        }
    }

    /** Jednostka semafora = 1 KiB (zaokrąglenie w górę), aby limit mieścił się w {@code int}. */
    private static long permits(long bytes) { return Math.max(1, (bytes + 1023) / 1024); }

    /**
     * Osobny RequestContext (system user) i ChangeSet — wzorzec workerów AInvoicer. Wyjątki DB/CAP są
     * zamieniane na stały kod bez SQL, hosta i nazw obiektów; naruszenie PK przechodzi do {@link #tryInsert}.
     */
    private <T> T inTransaction(Supplier<T> work) {
        try {
            return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                    runtime.changeSetContext().run(cs -> { return work.get(); }));
        } catch (ObjectStorageException error) {
            throw error;
        } catch (ServiceException error) {
            if (UNIQUE_VIOLATION.equals(error.getErrorStatus().getCodeString())) { throw error; }
            throw unavailable();
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    /** Odczyt strumienia BLOB z twardym limitem: dokładnie {@code expected} bajtów, inaczej uszkodzenie. */
    private static byte[] readContent(Object value, int expected) {
        byte[] bytes;
        if (value instanceof byte[] array) {
            bytes = array;
        } else if (value instanceof InputStream input) {
            try (input) {
                bytes = input.readNBytes(expected + 1);
            } catch (IOException error) {
                throw unavailable();
            }
        } else {
            throw new ObjectStorageException(ObjectStorageException.Category.CORRUPTED, "HANA_DOCUMENT_CORRUPTED");
        }
        if (bytes.length != expected) {
            throw new ObjectStorageException(ObjectStorageException.Category.CORRUPTED, "HANA_DOCUMENT_CORRUPTED");
        }
        return bytes;
    }

    private static long size(Row row) {
        Object value = row.get("sizeBytes");
        return value instanceof Number number ? number.longValue() : -1;
    }

    private static Instant instant(Object value) {
        if (value == null) { return null; }
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof OffsetDateTime offset) { return offset.toInstant(); }
        if (value instanceof java.sql.Timestamp timestamp) { return timestamp.toInstant(); }
        return Instant.parse(value.toString());
    }

    private static ObjectStorageException unavailable() {
        return new ObjectStorageException(ObjectStorageException.Category.UNAVAILABLE, "HANA_DOCUMENT_STORE_UNAVAILABLE");
    }

    @Override public String toString() { return "HanaBlobObjectStorageClient[" + ENTITY + "]"; }
}
