package customer.cap_java.ksef.fa3;

import java.util.Arrays;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * Zwalidowany dokument FA(3): dokładne bajty UTF-8 i ich SHA-256. Te same bajty należy zarejestrować
 * w {@link KsefInvoiceSnapshotRegistry} i przekazać do SDK KSeF — bez ponownej serializacji,
 * formatowania ani zmiany kodowania, bo każda zmiana bajtu zmienia skrót.
 * Instancja jest niemutowalna: tablica jest kopiowana przy tworzeniu i przy odczycie.
 */
public final class Fa3Document {
    /** Wartość schemaVersion oczekiwana przez {@link KsefInvoiceSnapshotRegistry#register}. */
    public static final String SCHEMA_VERSION = "FA(3)";

    private final byte[] xml;
    private final String sha256Hex;

    Fa3Document(byte[] xml) {
        this.xml = xml.clone();
        // Ten sam format (hex, małe litery) co migawka CAP i potwierdzenie przy akceptacji.
        this.sha256Hex = KsefInvoiceSnapshotRegistry.sha256Hex(this.xml);
    }

    /** Kopia dokładnych bajtów XML (UTF-8, bez BOM). */
    public byte[] xml() {
        return xml.clone();
    }

    public String sha256Hex() {
        return sha256Hex;
    }

    public int byteLength() {
        return xml.length;
    }

    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Fa3Document doc && Arrays.equals(xml, doc.xml);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(xml);
    }

    /** Bez treści XML — dokument zawiera dane podatników. */
    @Override
    public String toString() {
        return "Fa3Document[sha256=" + sha256Hex + ", bytes=" + xml.length + "]";
    }
}
