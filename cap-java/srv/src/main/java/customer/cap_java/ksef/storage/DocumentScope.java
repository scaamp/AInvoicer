package customer.cap_java.ksef.storage;

import java.util.Objects;

import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * Kontekst dostępu do dokumentu w magazynie: środowisko KSeF, kanał i właściciel partycji.
 *
 * <p>OUTBOUND: partycja per NIP podatnika wystawiającego (worker zna skonfigurowany NIP).
 * INBOUND: jeden dokument {@code (environment, ksefNumber)} jest współdzielony przez wiele grantów
 * {@code KsefInboundAccessGrants}; partycja jest więc per środowisko, a prawo odczytu daje wyłącznie
 * grant w CAP sprawdzony przed odczytem referencji. Identyczny SHA-256 w innym kanale/NIP to osobny
 * obiekt i osobna referencja — hash nigdy nie jest prawem dostępu.
 */
public record DocumentScope(KsefEnvironment environment, Channel channel, String ownerNip) {

    /** Kanał dokumentu; część klucza obiektu i referencji. */
    public enum Channel { OUTBOUND, INBOUND }

    static final String SHARED_OWNER = "-";

    public DocumentScope {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(channel, "channel");
        if (channel == Channel.OUTBOUND) {
            if (ownerNip == null || !ownerNip.matches("[0-9]{10}")) {
                throw new IllegalArgumentException("KSEF_DOCUMENT_SCOPE_INVALID");
            }
        } else if (ownerNip != null) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_SCOPE_INVALID");
        }
    }

    /** Dokumenty wysyłane przez podatnika (FA(3) i UPO jego sesji). */
    public static DocumentScope outbound(KsefEnvironment environment, String taxpayerNip) {
        return new DocumentScope(environment, Channel.OUTBOUND, taxpayerNip);
    }

    /** Staging INBOUND; autoryzacja przez granty CAP, nie przez klucz obiektu. */
    public static DocumentScope inbound(KsefEnvironment environment) {
        return new DocumentScope(environment, Channel.INBOUND, null);
    }

    String ownerSegment() { return ownerNip == null ? SHARED_OWNER : ownerNip; }
}
