package customer.cap_java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Predicate;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.*;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.request.UserInfo;

/** Odczyt stagingu bez uprawnień KSeF auth, synchronizacji, systemUser ani dostępu do XML. */
@Component
@ServiceName("KsefService")
public class InboundOverviewHandler implements EventHandler {
    private static final Set<String> SORT = Set.of("id", "acquisitionDate", "issueDate", "invoiceNumber",
            "ksefNumber", "sellerNip", "sellerName", "grossAmount", "currency");
    private static final Set<String> FILTER = Set.of("id", "environment", "invoiceNumber", "ksefNumber",
            "sellerNip", "issueDate", "acquisitionDate", "ingestionStatus", "parseStatus", "integrityStatus",
            "sapPostingStatus", "buyerIdentifierValue");

    /** CAP wykonuje projekcję i count w DB; AND z grantem obejmuje listę, klucz oraz /$count. */
    @Before(event = CqnService.EVENT_READ, entity = "KsefService.InboundInvoiceOverview")
    public void secureRead(CdsReadEventContext context) {
        for (String option : Set.of("$apply", "$search", "$expand", "$compute")) {
            if (context.getParameterInfo().getQueryParameter(option) != null) { throw invalid(); }
        }
        CqnSelect query = context.getCqn();
        validate(query);
        Predicate scope = allowed(context.getUserInfo());
        context.setCqn(CQL.copy(query, new Modifier() {
            @Override public CqnPredicate where(Predicate where) {
                return where == null ? scope : where.and(scope);
            }
            @Override public List<CqnSortSpecification> orderBy(List<CqnSortSpecification> order) {
                // Zapytanie count nie potrzebuje sortowania (niektóre DB odrzucają taki SQL).
                if (isCount(query)) { return order; }
                var stable = new ArrayList<>(order);
                if (stable.isEmpty()) { stable.add(CQL.get("acquisitionDate").desc()); }
                if (stable.stream().noneMatch(s -> s.value().isRef() && "id".equals(s.value().asRef().lastSegment()))) {
                    stable.add(CQL.get("id").asc());
                }
                var ordered = new ArrayList<CqnSortSpecification>();
                for (var sort : stable) {
                    // Jak w UI5 mock: NULL zawsze na końcu, niezależnie od kierunku i domyślnej polityki DB.
                    if (!"id".equals(sort.value().asRef().lastSegment())) {
                        ordered.add(CQL.when(CQL.get(sort.value().asRef().lastSegment()).isNull()).then(1).orElse(0).asc());
                    }
                    ordered.add(sort);
                }
                return ordered;
            }
            @Override public long top(long top) { return Math.min(top, 100); }
        }));
    }

    /** V1: dokładnie jeden poprawny NIP i CompanyCode; spółka jest kontekstem, nie własnością dokumentu. */
    private static Predicate allowed(UserInfo user) {
        List<String> nips = user.getAttributeValues("taxpayerNips");
        List<String> companies = user.getAttributeValues("companyCodes");
        if (!user.isAuthenticated() || !user.hasRole("KsefOperator")
                || nips == null || companies == null || nips.size() != 1 || companies.size() != 1
                || !customer.cap_java.ksef.inbound.KsefTaxpayerContext.isValidNip(nips.getFirst())
                || companies.getFirst() == null || !companies.getFirst().matches("[A-Z0-9]{4}")) {
            return CQL.get("id").isNull(); // UUID jest kluczem NOT NULL: fail closed także dla count.
        }
        return CQL.get("environment").eq("TEST").and(CQL.get("id").in(
                Select.from("customer.cap_java.KsefInboundAccessGrants").columns("documentId")
                    .where(g -> g.get("contextNip").eq(nips.getFirst()).and(g.get("subjectRole").eq("BUYER")))));
    }

    /** Whitelist CQN po parserze OData: brak apply/search/expand/nawigacji i kosztownych wyrażeń. */
    private static void validate(CqnSelect query) {
        if (!query.groupBy().isEmpty() || query.having().isPresent() || query.search().isPresent()
                || !query.transformations().isEmpty() || query.isDistinct() || query.skip() < 0) { throw invalid(); }
        query.where().ifPresent(p -> p.accept(new CqnVisitor() {
            private int nodes;
            @Override public void visit(CqnElementRef ref) {
                if (++nodes > 100 || ref.segments().size() != 1 || !FILTER.contains(ref.lastSegment())) { throw invalid(); }
            }
            @Override public void visit(CqnFunc function) {
                if (!Set.of("tolower", "lower", "contains", "startswith").contains(function.func())) { throw invalid(); }
            }
            @Override public void visit(CqnArithmeticExpression expression) { throw invalid(); }
            @Override public void visit(CqnInSubquery query) { throw invalid(); }
            @Override public void visit(CqnExistsSubquery query) { throw invalid(); }
            @Override public void visit(CqnPlain plain) { throw invalid(); }
            @Override public void visit(CqnComparisonPredicate comparison) {
                // CAP OData używa IS/IS_NOT również dla eq/ne (semantyka null OData).
                if (!Set.of("EQ", "NE", "IS", "IS_NOT", "GT", "GE", "LT", "LE").contains(comparison.operator().name())) { throw invalid(); }
            }
            @Override public void visit(CqnStringLiteral literal) {
                if (literal.value().length() > 256) { throw invalid(); }
            }
        }));
        for (var order : query.orderBy()) {
            if (!order.value().isRef() || order.value().asRef().segments().size() != 1
                    || !SORT.contains(order.value().asRef().lastSegment())) { throw invalid(); }
        }
        for (var item : query.items()) {
            if (item.isExpand() || item.isSelectList()) { throw invalid(); }
            if (item.isValue() && !item.isRef()) {
                var value = item.asValue().value();
                // /$count jest jedyną dozwoloną agregacją; $apply nie należy do kontraktu Inbox.
                if (!value.isFunction() || !"count".equals(value.asFunction().func())
                        || !value.asFunction().args().isEmpty()) { throw invalid(); }
            }
        }
    }

    private static boolean isCount(CqnSelect query) {
        return query.items().stream().anyMatch(i -> i.isValue() && i.asValue().value().isFunction()
                && "count".equals(i.asValue().value().asFunction().func()));
    }

    private static ServiceException invalid() {
        return new ServiceException(ErrorStatuses.BAD_REQUEST, "INBOX_QUERY_UNSUPPORTED");
    }
}
