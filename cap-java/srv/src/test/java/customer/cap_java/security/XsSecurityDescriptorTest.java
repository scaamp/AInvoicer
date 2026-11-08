package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deskryptor XSUAA (cap-java/xs-security.json) musi odpowiadać modelowi CDS po kompilacji (CSN z buildu CAP):
 * każda rola z @requires/@restrict ma scope $XSAPPNAME.&lt;rola&gt;, każdy $user.&lt;atrybut&gt; jest atrybutem
 * XSUAA wymaganym i powiązanym z szablonem roli. Generator `cds compile srv --to xsuaa` tworzy atrybuty
 * opcjonalne w osobnym szablonie z rolą domyślną — tego wariantu nie dopuszczamy (ADR-0006).
 */
class XsSecurityDescriptorTest {

    private static final Set<String> PSEUDO_ROLES = Set.of("authenticated-user", "any", "system-user", "internal-user");
    /** Wbudowane właściwości CAP UserInfo (np. $user.locale w sap.common texts) — nie są atrybutami XSUAA. */
    private static final Set<String> BUILT_IN_USER_PROPERTIES = Set.of("id", "locale", "tenant");
    private static final Pattern USER_ATTRIBUTE = Pattern.compile("\\$user\\.([A-Za-z_][A-Za-z0-9_]*)");
    private final ObjectMapper json = new ObjectMapper();

    @Test void descriptorMatchesCompiledCdsModelAndBindsAttributesToRole() throws Exception {
        JsonNode csn = json.readTree(Files.readString(Path.of("src/main/resources/edmx/csn.json")));
        JsonNode descriptor = json.readTree(Files.readString(Path.of("..", "xs-security.json")));

        Set<String> roles = new TreeSet<>();
        Set<String> attributes = new TreeSet<>();
        collect(csn, roles, attributes);
        attributes.removeAll(BUILT_IN_USER_PROPERTIES);
        assertEquals(Set.of("KsefOperator"), roles, "role w modelu CDS");
        assertEquals(Set.of("companyCodes", "taxpayerNips"), attributes, "atrybuty $user w modelu CDS");

        assertEquals("dedicated", descriptor.path("tenant-mode").asText());
        Set<String> scopes = new TreeSet<>();
        descriptor.path("scopes").forEach(scope -> scopes.add(scope.path("name").asText()));
        assertEquals(prefixed(roles), scopes);

        Set<String> declared = new TreeSet<>();
        descriptor.path("attributes").forEach(attribute -> {
            declared.add(attribute.path("name").asText());
            assertEquals("s", attribute.path("valueType").asText());
            assertTrue(attribute.path("valueRequired").asBoolean(false), "atrybut musi być wymagany: " + attribute);
        });
        assertEquals(attributes, declared);

        assertEquals(1, descriptor.path("role-templates").size(), "bez szablonu 'userattributes' i roli domyślnej");
        JsonNode template = descriptor.path("role-templates").get(0);
        assertEquals("KsefOperator", template.path("name").asText());
        assertFalse(template.has("default-role-name"));
        assertEquals(prefixed(roles), texts(template.path("scope-references")));
        assertEquals(attributes, texts(template.path("attribute-references")));
        assertFalse(descriptor.has("role-collections"), "role collection z wartościami tworzy administrator");
        assertFalse(descriptor.toString().contains("*"), "bez wildcard w scope/atrybutach");
    }

    private static Set<String> prefixed(Set<String> roles) {
        Set<String> result = new TreeSet<>();
        roles.forEach(role -> result.add("$XSAPPNAME." + role));
        return result;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> result = new TreeSet<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    /** Zbiera role z @requires i @restrict.to oraz atrybuty $user z wyrażeń where (tekst lub ref). */
    private static void collect(JsonNode node, Set<String> roles, Set<String> attributes) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getKey().equals("@requires")) {
                    addRoles(entry.getValue(), roles);
                }
                if (entry.getKey().equals("@restrict")) {
                    entry.getValue().forEach(rule -> addRoles(rule.path("to"), roles));
                }
                if (entry.getKey().equals("ref") && entry.getValue().isArray() && entry.getValue().size() == 2
                        && "$user".equals(entry.getValue().get(0).asText())) {
                    attributes.add(entry.getValue().get(1).asText());
                }
                collect(entry.getValue(), roles, attributes);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, roles, attributes));
        } else if (node.isTextual()) {
            Matcher matcher = USER_ATTRIBUTE.matcher(node.asText());
            while (matcher.find()) {
                attributes.add(matcher.group(1));
            }
        }
    }

    private static void addRoles(JsonNode value, Set<String> roles) {
        Set<String> values = new HashSet<>();
        if (value.isArray()) {
            value.forEach(role -> values.add(role.asText()));
        } else if (value.isTextual()) {
            values.add(value.asText());
        }
        values.removeAll(PSEUDO_ROLES);
        roles.addAll(values);
    }
}
