package customer.cap_java.ksef.inbound.sync;

import java.util.Objects;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;

/**
 * Zakres niezależnego checkpointu synchronizacji: środowisko + NIP kontekstu + rola podmiotu.
 * MF zaleca pobieranie osobno dla każdego typu podmiotu — checkpoint BUYER nie przesuwa SELLER i odwrotnie.
 */
public record SyncScope(KsefEnvironment environment, String contextNip, KsefSubjectRole role) {
    public SyncScope {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(role, "role");
        if (!KsefTaxpayerContext.isValidNip(contextNip)) {
            throw new IllegalArgumentException("KSEF_INBOUND_SCOPE_NIP_INVALID");
        }
    }

    public static SyncScope of(KsefTaxpayerContext context, KsefSubjectRole role) {
        return new SyncScope(context.environment(), context.nip(), role);
    }
}
