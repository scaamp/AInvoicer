package customer.cap_java.ksef.inbound.sync;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefNumbers;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;

/**
 * Czysta (bez I/O) polityka identyfikacji i deduplikacji dokumentów INBOUND. Rozdziela cztery pojęcia:
 * <ol>
 *   <li><b>Tożsamość dokumentu</b> — {@link DocumentIdentity}: {@code environment + ksefNumber}. Numer KSeF jest
 *       globalnie unikalny w danym środowisku; ten sam napis w TEST i PROD to dwa różne dokumenty.</li>
 *   <li><b>Uprawnienie dostępu</b> — {@link AccessGrant}: dokument + NIP kontekstu + rola. Każda para
 *       (kontekst, rola), w której KSeF pokazał dokument, to osobny wpis; dostęp jednego NIP nie daje dostępu
 *       innemu, a rola nie „rozszerza się” na inne role.</li>
 *   <li><b>Synchronizacja w kontekście</b> — checkpoint per {@link SyncScope} (osobno w {@code InvoiceSyncWindowPlanner}).</li>
 *   <li><b>Fingerprint treści</b> — {@link ContentFingerprint}: SHA-256 dokładnych bajtów XML + rozmiar.</li>
 * </ol>
 *
 * <p>Reguły: ponowny odczyt tego samego dokumentu nie tworzy nowego logicznego rekordu ({@code ALREADY_KNOWN}
 * albo tylko nowy grant/fingerprint); inna treść (hash) albo inne dane identyfikujące dla tej samej tożsamości
 * to {@code INTEGRITY_CONFLICT} — nigdy ciche nadpisanie. Polityka nie zakłada jednej globalnej tabeli
 * mieszającej zakresy autoryzacji; trwały model opisuje KSEF-IN-02.
 */
public final class InvoiceDeduplicationPolicy {
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** Tożsamość dokumentu: środowisko + numer KSeF (zwalidowany). */
    public record DocumentIdentity(KsefEnvironment environment, String ksefNumber) {
        public DocumentIdentity {
            Objects.requireNonNull(environment, "environment");
            KsefNumbers.requireValid(ksefNumber);
        }
    }

    /** Uprawnienie kontekstu (NIP) w danej roli do dokumentu — tak, jak zwrócił je KSeF. */
    public record AccessGrant(DocumentIdentity document, String contextNip, KsefSubjectRole role) {
        public AccessGrant {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(role, "role");
            if (!KsefTaxpayerContext.isValidNip(contextNip)) {
                throw new IllegalArgumentException("KSEF_INBOUND_GRANT_NIP_INVALID");
            }
        }
    }

    /** SHA-256 (hex, małe litery) dokładnych bajtów XML oraz ich rozmiar. */
    public record ContentFingerprint(String sha256Hex, long sizeBytes) {
        public ContentFingerprint {
            if (sha256Hex == null || !SHA256_HEX.matcher(sha256Hex).matches() || sizeBytes <= 0) {
                throw new IllegalArgumentException("KSEF_INBOUND_FINGERPRINT_INVALID");
            }
        }
    }

    /**
     * Znany (utrwalony) dokument. {@code fingerprint} jest {@code null}, dopóki XML nie został pobrany.
     * {@code buyerIdentifier} może być {@code null} (nabywca bez identyfikatora).
     */
    public record KnownDocument(DocumentIdentity identity, String invoiceNumber, String sellerNip, String buyerIdentifier,
            ContentFingerprint fingerprint, Set<AccessGrant> grants) {
        public KnownDocument {
            Objects.requireNonNull(identity, "identity");
            grants = Set.copyOf(grants);
            for (AccessGrant grant : grants) {
                if (!grant.document().equals(identity)) {
                    throw new IllegalArgumentException("KSEF_INBOUND_GRANT_FOREIGN_DOCUMENT");
                }
            }
        }
    }

    /** Nowa obserwacja dokumentu (z metadanych i — jeśli pobrano — z XML). */
    public record Observation(AccessGrant grant, String invoiceNumber, String sellerNip, String buyerIdentifier,
            ContentFingerprint fingerprint) {
        public Observation {
            Objects.requireNonNull(grant, "grant");
        }
    }

    public enum Outcome {
        /** Dokument nieznany — utwórz rekord dokumentu i grant. */
        NEW_DOCUMENT,
        /** Dokument znany i zgodny; zapisz tylko brakujący grant i/lub fingerprint. */
        UPDATE_KNOWN,
        /** Nic nowego — idempotentne powtórzenie. */
        ALREADY_KNOWN,
        /** Ta sama tożsamość, inna treść lub dane — wymaga wyjaśnienia; nic nie nadpisywać. */
        INTEGRITY_CONFLICT
    }

    /**
     * @param addGrant       trzeba dopisać grant obserwacji
     * @param addFingerprint trzeba zapisać fingerprint (dotąd nieznany)
     * @param conflictReason kod konfliktu dla {@code INTEGRITY_CONFLICT}, inaczej {@code null}
     */
    public record Decision(Outcome outcome, boolean addGrant, boolean addFingerprint, String conflictReason) {
    }

    /**
     * Rozstrzyga obserwację względem znanego dokumentu tej samej tożsamości ({@code null} = nieznany).
     */
    public Decision decide(KnownDocument existing, Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (existing == null) {
            return new Decision(Outcome.NEW_DOCUMENT, true, observation.fingerprint() != null, null);
        }
        if (!existing.identity().equals(observation.grant().document())) {
            throw new IllegalArgumentException("KSEF_INBOUND_IDENTITY_MISMATCH");
        }
        String conflict = conflict(existing, observation);
        if (conflict != null) {
            return new Decision(Outcome.INTEGRITY_CONFLICT, false, false, conflict);
        }
        boolean addGrant = !existing.grants().contains(observation.grant());
        boolean addFingerprint = existing.fingerprint() == null && observation.fingerprint() != null;
        return addGrant || addFingerprint
                ? new Decision(Outcome.UPDATE_KNOWN, addGrant, addFingerprint, null)
                : new Decision(Outcome.ALREADY_KNOWN, false, false, null);
    }

    /** Czy kontekst (środowisko + NIP) ma jakikolwiek grant do dokumentu. Brak grantu = brak dostępu. */
    public static boolean mayAccess(KnownDocument document, KsefTaxpayerContext context) {
        return document.identity().environment() == context.environment()
                && document.grants().stream().anyMatch(grant -> grant.contextNip().equals(context.nip()));
    }

    /** Czy kontekst ma grant w konkretnej roli (np. zakup = BUYER). */
    public static boolean mayAccessAs(KnownDocument document, KsefTaxpayerContext context, KsefSubjectRole role) {
        return document.identity().environment() == context.environment()
                && document.grants().stream()
                        .anyMatch(grant -> grant.contextNip().equals(context.nip()) && grant.role() == role);
    }

    private static String conflict(KnownDocument existing, Observation observation) {
        if (existing.fingerprint() != null && observation.fingerprint() != null
                && !existing.fingerprint().equals(observation.fingerprint())) {
            return "CONTENT_HASH_MISMATCH";
        }
        if (differs(existing.invoiceNumber(), observation.invoiceNumber())) {
            return "INVOICE_NUMBER_MISMATCH";
        }
        if (differs(existing.sellerNip(), observation.sellerNip())) {
            return "SELLER_NIP_MISMATCH";
        }
        if (differs(existing.buyerIdentifier(), observation.buyerIdentifier())) {
            return "BUYER_IDENTIFIER_MISMATCH";
        }
        return null;
    }

    private static boolean differs(String known, String observed) {
        return known != null && observed != null && !known.equals(observed);
    }
}
