package customer.cap_java.security;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sap.cds.services.request.ModifiableUserInfo;
import com.sap.cds.services.request.UserInfo;
import com.sap.cds.services.runtime.UserInfoProvider;

/**
 * Ochrona par NIP/CompanyCode (CAP-07, ADR-0006).
 *
 * <p>XSUAA (i lokalne mock users) dostarcza {@code taxpayerNips} i {@code companyCodes} jako dwie
 * niezależne listy; przy kilku rolach wartości są łączone. Warunek {@code @restrict}
 * {@code taxpayerNip = $user.taxpayerNips and companyCode = $user.companyCodes} dopuszcza wtedy
 * iloczyn kartezjański list. Iloczyn jest równy zbiorowi przyznanych par wyłącznie, gdy co najmniej
 * jedna lista ma jedną wartość. Gdy obie mają więcej wartości, para nie jest jednoznaczna — np.
 * NIP_A/1000 + NIP_B/2000 otworzyłoby niezamierzone NIP_A/2000.
 *
 * <p>Wtedy fail closed: oba atrybuty są zastępowane pustą listą. CAP traktuje pustą listę jako pełne
 * ograniczenie (brak rekordów), a {@code requestSend} odmawia 403. Nie rozszerzamy dostępu i nie
 * zgadujemy par. Docelowy model tuple (jeden atrybut z parą) wymaga osobnej decyzji i kolumny.
 *
 * <p>Provider jest beanem Spring typu {@link UserInfoProvider}; CAP rejestruje go po providerach
 * funkcji (XSUAA z cds-feature-identity lub mock), więc działa tak samo na BTP i lokalnie.
 */
@Component
public class KsefScopeUserInfoProvider implements UserInfoProvider {

    static final String TAXPAYER_NIPS = "taxpayerNips";
    static final String COMPANY_CODES = "companyCodes";

    private static final Logger log = LoggerFactory.getLogger(KsefScopeUserInfoProvider.class);

    private UserInfoProvider previous;

    @Override
    public void setPrevious(UserInfoProvider previous) {
        this.previous = previous;
    }

    @Override
    public UserInfo get() {
        return restrictAmbiguousScope(previous == null ? null : previous.get());
    }

    /** Zwraca tego samego użytkownika albo kopię bez atrybutów zakresu, gdy pary są niejednoznaczne. */
    static UserInfo restrictAmbiguousScope(UserInfo user) {
        if (user == null) {
            return null;
        }
        long nips = distinctValues(user.getAttributeValues(TAXPAYER_NIPS));
        long companies = distinctValues(user.getAttributeValues(COMPANY_CODES));
        if (nips <= 1 || companies <= 1) {
            return user;
        }
        // Bez wartości NIP/spółki w logu: tylko liczności, wystarczające do diagnozy przypisania ról.
        log.warn("Ambiguous KSeF authorization scope ({} taxpayer NIPs x {} company codes); access restricted to no data",
                nips, companies);
        ModifiableUserInfo restricted = user.copy();
        restricted.setAttributeValues(TAXPAYER_NIPS, List.of());
        restricted.setAttributeValues(COMPANY_CODES, List.of());
        return restricted;
    }

    private static long distinctValues(List<String> values) {
        if (values == null) {
            return 0;
        }
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
                .distinct().count();
    }
}
