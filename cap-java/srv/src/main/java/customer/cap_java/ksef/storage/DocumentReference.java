package customer.cap_java.ksef.storage;

import java.util.Locale;
import java.util.regex.Pattern;

import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * Logiczna referencja zapisywana w CAP ({@code xmlStorageRef}/{@code upoStorageRef}, String(500)):
 * {@code ksefdoc:v1:<ENV>:<CHANNEL>:<NIP|->:<sha256>}. Nie zawiera nazwy konta, kontenera, URL ani SAS,
 * więc przeniesienie do innej instancji Object Store nie wymaga migracji DB. Prefiks wdrożenia
 * (izolacja dev/test/prod) jest konfiguracją magazynu, nie częścią referencji.
 */
record DocumentReference(DocumentScope scope, String sha256) {
    static final String PREFIX = "ksefdoc:v1:";
    private static final Pattern FORMAT = Pattern.compile(
            "ksefdoc:v1:(TEST|DEMO|PROD):(OUTBOUND|INBOUND):([0-9]{10}|-):([a-f0-9]{64})");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");

    DocumentReference {
        if (sha256 == null || !SHA.matcher(sha256).matches()) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_REFERENCE_INVALID");
        }
    }

    /** Ścisłe parsowanie: brak normalizacji, wielkich liter, separatorów ścieżek ani znaków spoza wzorca. */
    static DocumentReference parse(String value) {
        if (value == null || value.length() > 200) { return null; }
        var matcher = FORMAT.matcher(value);
        if (!matcher.matches()) { return null; }
        var environment = KsefEnvironment.valueOf(matcher.group(1));
        var channel = DocumentScope.Channel.valueOf(matcher.group(2));
        String owner = DocumentScope.SHARED_OWNER.equals(matcher.group(3)) ? null : matcher.group(3);
        try {
            return new DocumentReference(new DocumentScope(environment, channel, owner), matcher.group(4));
        } catch (IllegalArgumentException invalidCombination) {
            return null; // np. INBOUND z NIP albo OUTBOUND bez NIP
        }
    }

    String value() {
        return PREFIX + scope.environment() + ":" + scope.channel() + ":" + scope.ownerSegment() + ":" + sha256;
    }

    /**
     * Klucz obiektu budowany wyłącznie ze zwalidowanych segmentów: brak '..', '\', '%', spacji i
     * ścieżek bezwzględnych, więc traversal/kolizja z cudzym prefiksem nie jest możliwa.
     */
    String objectKey(String keyPrefix) {
        String owner = scope.ownerNip() == null ? "shared" : scope.ownerNip();
        return keyPrefix + "/v1/" + scope.environment().name().toLowerCase(Locale.ROOT) + "/"
                + scope.channel().name().toLowerCase(Locale.ROOT) + "/" + owner + "/" + sha256 + ".xml";
    }

    /** Prefiks listingu jednej partycji (audyt orphan); kończy się '/', aby nie łapać sąsiednich NIP. */
    static String partitionPrefix(String keyPrefix, DocumentScope scope) {
        String owner = scope.ownerNip() == null ? "shared" : scope.ownerNip();
        return keyPrefix + "/v1/" + scope.environment().name().toLowerCase(Locale.ROOT) + "/"
                + scope.channel().name().toLowerCase(Locale.ROOT) + "/" + owner + "/";
    }

    /** Odwrotność {@link #objectKey}; obce lub zniekształcone klucze zwracają null. */
    static DocumentReference fromObjectKey(String keyPrefix, DocumentScope scope, String key) {
        String partition = partitionPrefix(keyPrefix, scope);
        if (key == null || !key.startsWith(partition) || !key.endsWith(".xml")) { return null; }
        String sha = key.substring(partition.length(), key.length() - 4);
        return SHA.matcher(sha).matches() ? new DocumentReference(scope, sha) : null;
    }
}
