package customer.cap_java;

import java.util.List;
import java.util.Optional;

import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Predicate;
import com.sap.cds.ql.Select;
import com.sap.cds.services.request.UserInfo;

import customer.cap_java.ksef.inbound.KsefTaxpayerContext;

/**
 * INT-02 / KSEF-IN-05: jeden model zaufanego zakresu odczytu dokumentów INBOUND, wspólny dla
 * {@link InboundOverviewHandler} (lista/klucz) i {@link InboundInvoiceXmlHandler} (pobranie XML).
 *
 * <p>Zakres pochodzi wyłącznie z uwierzytelnionego principala (XSUAA {@code xs.user.attributes} lub lokalny mock):
 * rola {@code KsefOperator}, dokładnie jeden poprawny NIP ({@code taxpayerNips}) i dokładnie jeden CompanyCode
 * ({@code companyCodes}, {@code [A-Z0-9]{4}}). CompanyCode jest <b>kontekstem</b> sesji (V1), nie własnością
 * dokumentu INBOUND — brak kolumny spółki w stagingu; wiele/błędne wartości = brak dostępu (fail closed).
 * Dostęp do dokumentu daje wyłącznie grant {@code KsefInboundAccessGrants (contextNip, BUYER)} w środowisku TEST;
 * pola faktury (np. {@code buyerIdentifierValue}) nigdy nie są podstawą widoczności.
 */
final class InboundGrantScope {
    static final String OPERATOR_ROLE = "KsefOperator";
    static final String ENVIRONMENT = "TEST";
    static final String SUBJECT_ROLE = "BUYER";
    static final String DOCUMENTS = "customer.cap_java.KsefInboundDocuments";
    static final String GRANTS = "customer.cap_java.KsefInboundAccessGrants";

    private InboundGrantScope() { }

    /** NIP kontekstu odczytu albo pusty wynik, gdy principal nie ma jednoznacznego, poprawnego zakresu. */
    static Optional<String> trustedBuyerNip(UserInfo user) {
        if (user == null || !user.isAuthenticated() || !user.hasRole(OPERATOR_ROLE)) { return Optional.empty(); }
        List<String> nips = user.getAttributeValues("taxpayerNips");
        List<String> companies = user.getAttributeValues("companyCodes");
        if (nips == null || companies == null || nips.size() != 1 || companies.size() != 1
                || !KsefTaxpayerContext.isValidNip(nips.getFirst())
                || companies.getFirst() == null || !companies.getFirst().matches("[A-Z0-9]{4}")) {
            return Optional.empty();
        }
        return Optional.of(nips.getFirst());
    }

    /**
     * Predykat widoczności dla encji z elementami {@code environment} i kluczem {@code idElement}
     * (projekcja OData: {@code id}; tabela stagingu: {@code ID}). Brak zakresu = predykat zawsze fałszywy
     * (klucz UUID jest NOT NULL), także dla {@code /$count}.
     */
    static Predicate visible(UserInfo user, String idElement) {
        return trustedBuyerNip(user)
                .map(nip -> CQL.get("environment").eq(ENVIRONMENT).and(CQL.get(idElement).in(grantedDocuments(nip))))
                .orElseGet(() -> CQL.get(idElement).isNull());
    }

    private static com.sap.cds.ql.cqn.CqnSelect grantedDocuments(String nip) {
        return Select.from(GRANTS).columns("documentId")
                .where(g -> g.get("contextNip").eq(nip).and(g.get("subjectRole").eq(SUBJECT_ROLE)));
    }
}
