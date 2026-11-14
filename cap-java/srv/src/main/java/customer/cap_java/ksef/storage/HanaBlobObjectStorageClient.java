package customer.cap_java.ksef.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.sap.cds.Row;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * KSEF-STOR-03: protokół {@link ObjectStorageClient} nad tabelą CAP {@code KsefDocuments} (HANA {@code BLOB},
 * lokalnie H2) wyłącznie przez CAP {@link PersistenceService} i CQN — bez JDBC, JPA i bez HTTP do własnego CAP.
 *
 * <p>Reguły KSeF (scope, referencja, SHA-256 referencji, retry) egzekwuje {@link ContentAddressedDocumentStore};
 * ta klasa odpowiada za mapowanie klucza obiektu na wiersz i za oddzielenie wierszy magazynu od wierszy
 * laboratoryjnego {@code BlobService}:
 * <ul>
 *   <li>{@code fileName} = klucz obiektu ({@code <prefix>/v1/<env>/<channel>/<owner>/<sha>.xml}); laboratoryjny
 *       handler odrzuca '/' w nazwie, więc użytkownik OData nie utworzy wiersza o takim kluczu;</li>
 *   <li>{@code createdBy}/{@code modifiedBy} = {@link #STORE_PRINCIPAL}; OData ignoruje {@code createdBy}
 *       z payloadu (managed + {@code @readonly}), więc wiersz klienta nigdy nie spełni filtra magazynu;</li>
 *   <li>{@code ID} = deterministyczny UUID z klucza — klucz główny działa jak ograniczenie UNIQUE przy
 *       współbieżnym zapisie. Publiczny POST akceptuje ID od klienta (zweryfikowane 14.11.2026), więc ID może
 *       zostać „zajęte”: wtedy zapis używa losowego ID, a odczyt i tak szuka po (fileName, createdBy), nie po ID.</li>
 * </ul>
 * Wiersze magazynu nie są nigdy aktualizowane ani usuwane. Każdy zapis/odczyt to osobny RequestContext
 * (system user) i osobny ChangeSet: zapis jest trwały niezależnie od transakcji wywołującego, tak jak PUT
 * w Object Store — referencję CAP finalizuje dopiero wywołujący.
 *
 * <p>Ograniczenie wynikające ze schematu (decyzja właściciela, zob. przewodnik KSEF-STOR-03): wiersze współdzielą
 * tabelę z laboratorium i są ukryte przed {@code BlobService} wyłącznie filtrem {@code createdBy = $user}.
 * Użytkownik IdP o nazwie równej {@link #STORE_PRINCIPAL} widziałby je przez OData.
 */
final class HanaBlobObjectStorageClient implements ObjectStorageClient {
    static final String ENTITY = "customer.cap_java.KsefDocuments";
    /** Zarezerwowany principal wierszy magazynu; ':' i prefiks URN nie występują w nazwach użytkowników XSUAA/IAS projektu. */
    static final String STORE_PRINCIPAL = "urn:ainvoicer:ksef-document-store";
    static final String MIME_TYPE = "application/xml";
    /** Przestrzeń nazw deterministycznego ID; zmiana = nowa przestrzeń kluczy (stare wiersze nadal czytelne po fileName). */
    private static final String ID_NAMESPACE = "ainvoicer:ksef-stor-03:v1:";
    private static final Pattern KEY = Pattern.compile(
            "[a-z0-9][a-z0-9-]{1,62}/v1/(test|demo|prod)/(outbound|inbound)/([0-9]{10}|shared)/[a-f0-9]{64}\\.xml");
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}/v1/[a-z0-9/]*");
    private static final String UNIQUE_VIOLATION = "409006";

    private final PersistenceService db;
    private final CdsRuntime runtime;
    private final int maxBytes;

    HanaBlobObjectStorageClient(PersistenceService db, CdsRuntime runtime, int maxBytes) {
        this.db = Objects.requireNonNull(db);
        this.runtime = Objects.requireNonNull(runtime);
        if (maxBytes < 1) { throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_LIMIT_INVALID"); }
        this.maxBytes = maxBytes;
    }

    /**
     * Create-only: INSERT pod deterministycznym ID. Naruszenie PK (409006) oznacza wyścig lub ponowienie —
     * wtedy decyduje istnienie wiersza magazynu o tym kluczu. Brak takiego wiersza = ID zajęte przez obcy
     * wiersz (np. POST laboratorium z jawnym ID); zapis z losowym ID, bez dotykania obcego wiersza.
     * Inne błędy DB = UNAVAILABLE (ponowienie bezpieczne, bo INSERT jest atomowy i create-only).
     */
    @Override
    public CreateOutcome createIfAbsent(String key, byte[] content, Map<String, String> metadata) {
        requireKey(key);
        if (content == null || content.length == 0 || content.length > maxBytes) {
            throw new ObjectStorageException(ObjectStorageException.Category.TOO_LARGE, "HANA_DOCUMENT_SIZE_INVALID");
        }
        byte[] bytes = content.clone();
        String sha256 = KsefInvoiceSnapshotRegistry.sha256Hex(bytes); // backend liczy hash z zapisywanych bajtów
        if (tryInsert(deterministicId(key), key, bytes, sha256)) { return CreateOutcome.CREATED; }
        if (findRowId(key).isPresent()) { return CreateOutcome.ALREADY_EXISTS; }
        if (tryInsert(UUID.randomUUID().toString(), key, bytes, sha256)) { return CreateOutcome.CREATED; }
        // Losowy UUID w konflikcie: praktycznie niemożliwe; nie zgadujemy, raportujemy chwilowy błąd.
        throw unavailable();
    }

    /** Odczyt najstarszego wiersza magazynu o kluczu; BLOB czytany w całości w tej samej transakcji. */
    @Override
    public Optional<StoredObject> read(String key, int limit) {
        requireKey(key);
        int effective = Math.min(limit, maxBytes);
        return inTransaction(() -> {
            var row = db.run(Select.from(ENTITY).columns("ID", "content", "sha256", "mimeType")
                    .where(d -> d.get("fileName").eq(key).and(d.get("createdBy").eq(STORE_PRINCIPAL)))
                    .orderBy(d -> d.get("createdAt").asc(), d -> d.get("ID").asc())
                    .limit(1)).first();
            if (row.isEmpty()) { return Optional.<StoredObject>empty(); }
            if (!MIME_TYPE.equals(row.get().get("mimeType"))) {
                throw new ObjectStorageException(ObjectStorageException.Category.CORRUPTED, "HANA_DOCUMENT_CORRUPTED");
            }
            byte[] bytes = readContent(row.get().get("content"), effective);
            Object declared = row.get().get("sha256");
            // Brak "ainvlength": długość nie jest osobno utrwalana; SHA-256 kolumny i treści weryfikuje ContentAddressed.
            return Optional.of(new StoredObject(bytes, declared == null ? Map.of() : Map.of("ainvsha256", declared.toString())));
        });
    }

    /** Listing kluczy partycji dla audytu orphan, stronicowany po {@code fileName}; nie czyta BLOB (size = -1). */
    @Override
    public ListPage list(String prefix, String marker, int maxResults) {
        if (prefix == null || !PREFIX.matcher(prefix).matches() || maxResults < 1 || maxResults > 5000
                || marker != null && !KEY.matcher(marker).matches()) {
            throw new ObjectStorageException(ObjectStorageException.Category.REJECTED, "HANA_DOCUMENT_LIST_INVALID");
        }
        return inTransaction(() -> {
            var query = Select.from(ENTITY).columns("fileName", "createdAt")
                    .where(d -> {
                        var filter = d.get("createdBy").eq(STORE_PRINCIPAL).and(d.get("fileName").startsWith(prefix));
                        return marker == null ? filter : filter.and(d.get("fileName").gt(marker));
                    })
                    .orderBy(d -> d.get("fileName").asc())
                    .limit(maxResults + 1);
            var objects = new ArrayList<ListedObject>();
            String last = null;
            boolean more = false;
            for (Row row : db.run(query).list()) {
                String key = (String) row.get("fileName");
                if (key.equals(last)) { continue; } // duplikat po zajętym ID — jeden logiczny obiekt
                if (objects.size() == maxResults) { more = true; break; }
                objects.add(new ListedObject(key, -1, instant(row.get("createdAt"))));
                last = key;
            }
            return new ListPage(List.copyOf(objects), more ? last : null);
        });
    }

    /** ID wiersza dla klucza: UUID v3 (nazwa) — nigdy równy UUID v4 generowanemu przez CAP dla POST bez ID. */
    static String deterministicId(String key) {
        return UUID.nameUUIDFromBytes((ID_NAMESPACE + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean tryInsert(String id, String key, byte[] bytes, String sha256) {
        try {
            inTransaction(() -> db.run(Insert.into(ENTITY).entry(Map.of(
                    "ID", id, "fileName", key, "mimeType", MIME_TYPE,
                    "content", new ByteArrayInputStream(bytes), "sha256", sha256,
                    "createdBy", STORE_PRINCIPAL, "modifiedBy", STORE_PRINCIPAL))));
            return true;
        } catch (ServiceException error) {
            if (UNIQUE_VIOLATION.equals(error.getErrorStatus().getCodeString())) { return false; }
            throw unavailable();
        }
    }

    private Optional<Object> findRowId(String key) {
        return inTransaction(() -> db.run(Select.from(ENTITY).columns("ID")
                .where(d -> d.get("fileName").eq(key).and(d.get("createdBy").eq(STORE_PRINCIPAL)))
                .limit(1)).first().map(row -> row.get("ID")));
    }

    /**
     * Osobny RequestContext (system user) i ChangeSet — ten sam wzorzec co workery AInvoicer. Wyjątki
     * DB/CAP są zamieniane na stały kod bez treści, SQL i nazw obiektów; wyjątek naruszenia PK przechodzi
     * dalej do {@link #tryInsert}.
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

    private static byte[] readContent(Object value, int limit) {
        if (value == null) { return new byte[0]; } // pusty = błąd integralności w ContentAddressed
        byte[] bytes;
        if (value instanceof byte[] array) {
            bytes = array;
        } else if (value instanceof InputStream input) {
            try (input) {
                bytes = input.readNBytes(limit + 1);
            } catch (IOException error) {
                throw unavailable();
            }
        } else {
            throw new ObjectStorageException(ObjectStorageException.Category.CORRUPTED, "HANA_DOCUMENT_CORRUPTED");
        }
        if (bytes.length > limit) {
            throw new ObjectStorageException(ObjectStorageException.Category.TOO_LARGE, "HANA_DOCUMENT_SIZE_INVALID");
        }
        return bytes;
    }

    private static Instant instant(Object value) {
        if (value == null) { return null; }
        return value instanceof Instant instant ? instant : Instant.parse(value.toString());
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ObjectStorageException(ObjectStorageException.Category.REJECTED, "HANA_DOCUMENT_KEY_INVALID");
        }
    }

    private static ObjectStorageException unavailable() {
        return new ObjectStorageException(ObjectStorageException.Category.UNAVAILABLE, "HANA_DOCUMENT_STORE_UNAVAILABLE");
    }

    @Override public String toString() { return "HanaBlobObjectStorageClient[" + ENTITY + "]"; }
}
