package customer.cap_java.ksef.inbound.sync;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.sync.InvoiceDeduplicationPolicy.*;

/** Tożsamość, dostęp, fingerprint i konflikty integralności — bez bazy CAP. */
class InvoiceDeduplicationPolicyTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private final InvoiceDeduplicationPolicy policy = new InvoiceDeduplicationPolicy();
    private final DocumentIdentity doc = new DocumentIdentity(KsefEnvironment.TEST, ksefNumber(1));

    @Test
    void sameDocumentReadAgainDoesNotCreateAnotherLogicalRecord() {
        var ledger = new Ledger();
        var observation = observation(CONTEXT_NIP, KsefSubjectRole.BUYER, HASH_A);
        assertEquals(Outcome.NEW_DOCUMENT, ledger.apply(observation).outcome());
        assertEquals(Outcome.ALREADY_KNOWN, ledger.apply(observation).outcome());
        assertEquals(Outcome.ALREADY_KNOWN, ledger.apply(observation).outcome());
        assertEquals(1, ledger.documents.size());
        assertEquals(1, ledger.documents.get(doc).grants().size());
    }

    @Test
    void metadataFirstThenFingerprintIsAnUpdateNotADuplicate() {
        var ledger = new Ledger();
        assertEquals(Outcome.NEW_DOCUMENT, ledger.apply(observation(CONTEXT_NIP, KsefSubjectRole.BUYER, null)).outcome());
        var decision = ledger.apply(observation(CONTEXT_NIP, KsefSubjectRole.BUYER, HASH_A));
        assertEquals(Outcome.UPDATE_KNOWN, decision.outcome());
        assertTrue(decision.addFingerprint());
        assertFalse(decision.addGrant());
        assertEquals(1, ledger.documents.size());
    }

    @Test
    void sameKsefNumberWithDifferentHashIsIntegrityConflictAndNotOverwritten() {
        var ledger = new Ledger();
        ledger.apply(observation(CONTEXT_NIP, KsefSubjectRole.BUYER, HASH_A));
        var decision = ledger.apply(observation(CONTEXT_NIP, KsefSubjectRole.BUYER, HASH_B));
        assertEquals(Outcome.INTEGRITY_CONFLICT, decision.outcome());
        assertEquals("CONTENT_HASH_MISMATCH", decision.conflictReason());
        assertEquals(new ContentFingerprint(HASH_A, 100), ledger.documents.get(doc).fingerprint());
    }

    @Test
    void differentIdentifyingDataForSameIdentityIsConflict() {
        var known = new KnownDocument(doc, "FZ/1", SELLER_NIP, CONTEXT_NIP, null,
                Set.of(new AccessGrant(doc, CONTEXT_NIP, KsefSubjectRole.BUYER)));
        var changedNumber = new Observation(new AccessGrant(doc, CONTEXT_NIP, KsefSubjectRole.BUYER), "FZ/2",
                SELLER_NIP, CONTEXT_NIP, null);
        assertEquals("INVOICE_NUMBER_MISMATCH", policy.decide(known, changedNumber).conflictReason());
        var changedSeller = new Observation(new AccessGrant(doc, CONTEXT_NIP, KsefSubjectRole.BUYER), "FZ/1",
                OTHER_NIP, CONTEXT_NIP, null);
        assertEquals("SELLER_NIP_MISMATCH", policy.decide(known, changedSeller).conflictReason());
    }

    @Test
    void twoRolesProduceTwoGrantsWithoutMixingAccess() {
        var ledger = new Ledger();
        ledger.apply(observation(CONTEXT_NIP, KsefSubjectRole.BUYER, HASH_A));
        var asThirdParty = ledger.apply(observation(CONTEXT_NIP, KsefSubjectRole.THIRD_PARTY, HASH_A));
        assertEquals(Outcome.UPDATE_KNOWN, asThirdParty.outcome());
        assertTrue(asThirdParty.addGrant());
        KnownDocument stored = ledger.documents.get(doc);
        assertEquals(2, stored.grants().size());

        var context = new KsefTaxpayerContext(KsefEnvironment.TEST, CONTEXT_NIP);
        var other = new KsefTaxpayerContext(KsefEnvironment.TEST, OTHER_NIP);
        assertTrue(InvoiceDeduplicationPolicy.mayAccess(stored, context));
        assertTrue(InvoiceDeduplicationPolicy.mayAccessAs(stored, context, KsefSubjectRole.BUYER));
        assertTrue(InvoiceDeduplicationPolicy.mayAccessAs(stored, context, KsefSubjectRole.THIRD_PARTY));
        // Rola nie rozszerza się na inne role, a grant jednego NIP nie daje dostępu innemu.
        assertFalse(InvoiceDeduplicationPolicy.mayAccessAs(stored, context, KsefSubjectRole.SELLER));
        assertFalse(InvoiceDeduplicationPolicy.mayAccess(stored, other));
        assertFalse(InvoiceDeduplicationPolicy.mayAccess(stored, new KsefTaxpayerContext(KsefEnvironment.PROD, CONTEXT_NIP)));
    }

    @Test
    void environmentsAreSeparateIdentities() {
        var test = new DocumentIdentity(KsefEnvironment.TEST, ksefNumber(1));
        var prod = new DocumentIdentity(KsefEnvironment.PROD, ksefNumber(1));
        assertNotEquals(test, prod);
        var ledger = new Ledger();
        ledger.apply(new Observation(new AccessGrant(test, CONTEXT_NIP, KsefSubjectRole.BUYER), "FZ/1", SELLER_NIP,
                CONTEXT_NIP, new ContentFingerprint(HASH_A, 100)));
        var decision = ledger.apply(new Observation(new AccessGrant(prod, CONTEXT_NIP, KsefSubjectRole.BUYER), "FZ/1",
                SELLER_NIP, CONTEXT_NIP, new ContentFingerprint(HASH_B, 100)));
        assertEquals(Outcome.NEW_DOCUMENT, decision.outcome());
        assertEquals(2, ledger.documents.size());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentIdentity(KsefEnvironment.TEST, "x"));
        assertThrows(IllegalArgumentException.class, () -> new ContentFingerprint("ABC", 1));
        assertThrows(IllegalArgumentException.class, () -> new AccessGrant(doc, "123", KsefSubjectRole.BUYER));
        var foreign = new DocumentIdentity(KsefEnvironment.TEST, ksefNumber(2));
        assertThrows(IllegalArgumentException.class, () -> new KnownDocument(doc, null, null, null, null,
                Set.of(new AccessGrant(foreign, CONTEXT_NIP, KsefSubjectRole.BUYER))));
    }

    private Observation observation(String nip, KsefSubjectRole role, String hash) {
        return new Observation(new AccessGrant(doc, nip, role), "FZ/1", SELLER_NIP, CONTEXT_NIP,
                hash == null ? null : new ContentFingerprint(hash, 100));
    }

    /** Minimalny rejestr w pamięci pokazujący, jak trwała implementacja ma stosować decyzje. */
    private final class Ledger {
        final Map<DocumentIdentity, KnownDocument> documents = new HashMap<>();

        Decision apply(Observation observation) {
            DocumentIdentity identity = observation.grant().document();
            KnownDocument known = documents.get(identity);
            Decision decision = policy.decide(known, observation);
            switch (decision.outcome()) {
                case NEW_DOCUMENT -> documents.put(identity, new KnownDocument(identity, observation.invoiceNumber(),
                        observation.sellerNip(), observation.buyerIdentifier(), observation.fingerprint(),
                        Set.of(observation.grant())));
                case UPDATE_KNOWN -> {
                    var grants = new HashSet<>(known.grants());
                    if (decision.addGrant()) {
                        grants.add(observation.grant());
                    }
                    documents.put(identity, new KnownDocument(identity, known.invoiceNumber(), known.sellerNip(),
                            known.buyerIdentifier(), decision.addFingerprint() ? observation.fingerprint()
                                    : known.fingerprint(), grants));
                }
                default -> {
                    // ALREADY_KNOWN: nic; INTEGRITY_CONFLICT: nic nie nadpisujemy.
                }
            }
            return decision;
        }
    }
}
