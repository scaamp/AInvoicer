package customer.cap_java.sap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

/**
 * Utrwala niezmienną migawkę tego, co przygotowano do wysyłki do KSeF: UUID faktury, kontekst,
 * wersję nagłówka SAP, odcisk pozycji i SHA-256 dokładnych bajtów XML. To wyłącznie dowód przygotowania
 * dokumentu — nie wysyła niczego do KSeF i nie jest potwierdzeniem akceptacji. Brak API zmiany/usunięcia.
 * Wywołujący (przyszły generator FA(3)) dostarcza bajty XML; ten komponent ich nie tworzy.
 */
@Component
public class KsefInvoiceSnapshotRegistry {
    private static final int MAX_XML_BYTES = 10 * 1024 * 1024;
    private final PersistenceService db;

    public KsefInvoiceSnapshotRegistry(PersistenceService db) {
        this.db = db;
    }

    /** SHA-256 jako małe litery hex; ten sam format jest porównywany z potwierdzeniem przy akceptacji. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Odcisk pozycji = SHA-256 kanonicznego tekstu "ItemUUID|LocalLastChangedAt" posortowanego po ItemUUID.
     * Zmiana, dodanie lub usunięcie pozycji zmienia odcisk; sama wersja nagłówka tego nie wykrywa.
     */
    public static String itemsFingerprint(List<Map<String, Object>> items) {
        var lines = items.stream().map(item -> {
            Object id = item.get("ItemUUID");
            Object version = item.get("LocalLastChangedAt");
            if (id == null || version == null) {
                throw new IllegalArgumentException("ITEM_VERSION_MISSING");
            }
            return id.toString().toLowerCase() + "|" + version;
        }).sorted().toList();
        return sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** Wersja nagłówka jako tekst ISO z pełną precyzją 100 ns (zgodna z SapResultWrites.expectedVersion). */
    public static String headerVersion(Map<String, Object> header) {
        Object value = header.get("LocalLastChangedAt");
        if (value == null) {
            throw new IllegalArgumentException("HEADER_VERSION_MISSING");
        }
        Instant version = value instanceof Instant instant ? instant : Instant.parse(value.toString());
        return version.toString();
    }

    /** Wynik porównania migawki zapisanej przy przygotowaniu XML z bieżącym stanem SAP. */
    public enum Drift { UNCHANGED, HEADER_CHANGED, ITEMS_CHANGED, HEADER_AND_ITEMS_CHANGED }

    public static Drift detectDrift(Map<String, Object> storedSnapshot, SapInvoiceSnapshot current) {
        boolean header = !storedSnapshot.get("sapHeaderVersion").equals(headerVersion(current.header()));
        boolean items = !storedSnapshot.get("itemsFingerprint").equals(itemsFingerprint(current.items()));
        return header ? (items ? Drift.HEADER_AND_ITEMS_CHANGED : Drift.HEADER_CHANGED)
                : (items ? Drift.ITEMS_CHANGED : Drift.UNCHANGED);
    }

    /**
     * Rejestruje migawkę w aktywnej transakcji. Ten sam dokument (faktura + środowisko + podatnik + SHA-256)
     * zwraca istniejące ID; inny XML tej samej faktury tworzy nową, niezmienną migawkę.
     */
    public UUID register(SapInvoiceSnapshot sap, String taxpayerNip, String companyCode, String environment,
            String schemaVersion, byte[] xml) {
        return register(sap, taxpayerNip, companyCode, environment, schemaVersion, xml, SOURCE_UNVERIFIED);
    }

    /** Pochodzenie migawki: tylko odczyt RAP wykonany przez CAP daje prawo do późniejszego writebacku. */
    public static final String SOURCE_SAP_RAP = "SAP_RAP";
    public static final String SOURCE_UNVERIFIED = "UNVERIFIED";

    public UUID register(SapInvoiceSnapshot sap, String taxpayerNip, String companyCode, String environment,
            String schemaVersion, byte[] xml, String source) {
        if (!SOURCE_SAP_RAP.equals(source) && !SOURCE_UNVERIFIED.equals(source)) {
            throw new IllegalArgumentException("SNAPSHOT_SOURCE_INVALID");
        }
        var header = sap.header();
        if (xml == null || xml.length == 0 || xml.length > MAX_XML_BYTES
                || !"TEST".equals(environment) || !"FA(3)".equals(schemaVersion)
                || !"OUTBOUND".equals(header.get("Direction"))
                || !taxpayerNip.equals(header.get("SellerNip"))
                || !companyCode.equals(header.get("CompanyCode"))) {
            throw new IllegalArgumentException("SNAPSHOT_SCOPE_INVALID");
        }
        UUID invoiceId = UUID.fromString(header.get("InvoiceUUID").toString());
        if (sap.items().stream().anyMatch(item -> !invoiceId.toString().equalsIgnoreCase(
                String.valueOf(item.get("InvoiceUUID"))))) {
            throw new IllegalArgumentException("SNAPSHOT_ITEM_SCOPE_INVALID");
        }
        String hash = sha256Hex(xml);
        var existing = db.run(Select.from(SapKsefResultWriteback.SNAPSHOTS).columns("ID", "source").where(s ->
                s.get("invoiceUUID").eq(invoiceId.toString()).and(s.get("environment").eq(environment))
                        .and(s.get("taxpayerNip").eq(taxpayerNip)).and(s.get("xmlSha256").eq(hash)))).first();
        if (existing.isPresent()) {
            // Ten sam dokument nie może zmienić pochodzenia (np. fixture nie staje się „zweryfikowanym RAP”).
            if (!source.equals(existing.get().get("source"))) {
                throw new IllegalArgumentException("SNAPSHOT_SOURCE_CONFLICT");
            }
            return UUID.fromString((String) existing.get().get("ID"));
        }
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id.toString());
        row.put("invoiceUUID", invoiceId.toString());
        row.put("taxpayerNip", taxpayerNip);
        row.put("direction", "OUTBOUND");
        row.put("environment", environment);
        row.put("companyCode", companyCode);
        row.put("schemaVersion", schemaVersion);
        row.put("sapHeaderVersion", headerVersion(header));
        row.put("itemsFingerprint", itemsFingerprint(sap.items()));
        row.put("xmlSha256", hash);
        row.put("xmlByteLength", xml.length);
        row.put("source", source);
        db.run(Insert.into(SapKsefResultWriteback.SNAPSHOTS).entry(row));
        return id;
    }
}
