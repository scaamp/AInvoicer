package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sap.cds.services.request.UserInfo;

/** Jednostkowo: kiedy listy NIP/CompanyCode są jednoznaczne, a kiedy provider zamyka dostęp. */
class KsefScopeUserInfoProviderTest {

    private static UserInfo user(List<String> nips, List<String> companies) {
        var user = UserInfo.create().setName("u").setIsAuthenticated(true).addRole("KsefOperator");
        if (nips != null) { user.setAttributeValues("taxpayerNips", nips); }
        if (companies != null) { user.setAttributeValues("companyCodes", companies); }
        return user;
    }

    @Test void singlePairAndSingleSidedListsAreKeptUnchanged() {
        for (var unchanged : List.of(user(List.of("1234567802"), List.of("1000")),
                user(List.of("1234567802"), List.of("1000", "2000")),
                user(List.of("1234567802", "9876543210"), List.of("1000")),
                user(null, List.of("1000", "2000")), user(List.of(), List.of()))) {
            assertSame(unchanged, KsefScopeUserInfoProvider.restrictAmbiguousScope(unchanged));
        }
    }

    @Test void crossProductOfTwoMultiValueListsFailsClosed() {
        var original = user(List.of("1234567802", "9876543210"), List.of("1000", "2000"));
        var restricted = KsefScopeUserInfoProvider.restrictAmbiguousScope(original);
        assertNotSame(original, restricted);
        assertEquals(List.of(), restricted.getAttributeValues("taxpayerNips"));
        assertEquals(List.of(), restricted.getAttributeValues("companyCodes"));
        // Tożsamość i rola pozostają; zmienia się wyłącznie zakres danych.
        assertTrue(restricted.isAuthenticated());
        assertTrue(restricted.hasRole("KsefOperator"));
        assertEquals("u", restricted.getName());
        // Oryginał niezmieniony (provider nie mutuje UserInfo poprzednika).
        assertEquals(2, original.getAttributeValues("taxpayerNips").size());
    }

    @Test void duplicatesAndBlanksDoNotCreateAmbiguity() {
        var user = user(List.of("1234567802", "1234567802 ", ""), List.of("1000", "2000"));
        assertSame(user, KsefScopeUserInfoProvider.restrictAmbiguousScope(user));
    }

    @Test void chainsPreviousProviderAndToleratesMissingUser() {
        var provider = new KsefScopeUserInfoProvider();
        assertNull(provider.get());
        provider.setPrevious(() -> user(List.of("1234567802", "9876543210"), List.of("1000", "2000")));
        assertEquals(List.of(), provider.get().getAttributeValues("companyCodes"));
    }
}
