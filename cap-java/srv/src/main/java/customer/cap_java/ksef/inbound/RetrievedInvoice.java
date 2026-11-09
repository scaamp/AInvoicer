package customer.cap_java.ksef.inbound;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Dokładne bajty XML faktury pobrane z {@code GET /invoices/ksef/{ksefNumber}} oraz ich SHA-256.
 *
 * <p>Bajty nie są dekodowane ani ponownie serializowane — skrót liczony jest z oryginału i to oryginał
 * należy utrwalić (przyszły {@code KsefDocumentStore}). Obiekt kopiuje tablicę na wejściu i wyjściu,
 * a {@link #toString()} nie zawiera treści dokumentu.
 */
public final class RetrievedInvoice {
    private final KsefEnvironment environment;
    private final String ksefNumber;
    private final byte[] content;
    private final byte[] sha256;

    RetrievedInvoice(KsefEnvironment environment, String ksefNumber, byte[] content) {
        this.environment = Objects.requireNonNull(environment);
        this.ksefNumber = KsefNumbers.requireValid(ksefNumber);
        this.content = content.clone();
        this.sha256 = sha256(this.content);
    }

    public KsefEnvironment environment() {
        return environment;
    }

    public String ksefNumber() {
        return ksefNumber;
    }

    /** Kopia oryginalnych bajtów XML. */
    public byte[] content() {
        return content.clone();
    }

    public int size() {
        return content.length;
    }

    public String sha256Hex() {
        return HexFormat.of().formatHex(sha256);
    }

    /** Ten sam format co {@code invoiceHash} w metadanych KSeF. */
    public String sha256Base64() {
        return Base64.getEncoder().encodeToString(sha256);
    }

    /** Porównanie w czasie stałym ze skrótem {@code invoiceHash} z metadanych KSeF. */
    public boolean matchesKsefHash(String invoiceHashBase64) {
        if (!InvoiceMetadataMapper.isSha256Base64(invoiceHashBase64)) {
            return false;
        }
        return MessageDigest.isEqual(sha256, Base64.getDecoder().decode(invoiceHashBase64));
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "RetrievedInvoice[" + environment + ", " + ksefNumber + ", " + content.length + " B, sha256=" + sha256Hex() + "]";
    }
}
