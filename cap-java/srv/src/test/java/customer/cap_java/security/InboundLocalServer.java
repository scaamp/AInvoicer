package customer.cap_java.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import com.sap.cds.services.persistence.PersistenceService;

/**
 * Testowy serwer do UI5 → prawdziwe OData/H2; klasa i fixture nie trafiają do produkcyjnego JAR.
 *
 * <p>BETA-02: opcjonalny tryb lokalnego UAT ({@code -Dainv.inbox.local.uat-xml=true}, ustawiany wyłącznie przez
 * {@code npm run start:beta-uat}) włącza ten sam backend pobrania XML co beta: magazyn {@code KsefStoredDocuments}
 * na <b>lokalnym H2</b> ({@code allow-local-h2}, w chmurze ignorowane) i {@code xml-download}. KSeF/SAP/SEND OFF.
 */
public final class InboundLocalServer {
    static final String UAT_XML = "ainv.inbox.local.uat-xml";

    public static void main(String[] args) {
        if (System.getenv("VCAP_APPLICATION") != null || System.getenv("VCAP_SERVICES") != null || args.length != 0) {
            throw new IllegalStateException("INBOX_LOCAL_HARNESS_ONLY");
        }
        boolean uatXml = Boolean.parseBoolean(System.getProperty(UAT_XML, "false"));
        var app = new SpringApplication(customer.cap_java.Application.class);
        app.setAdditionalProfiles("test");
        // Argumenty CLI mają pierwszeństwo przed środowiskiem: żaden lokalny env nie włączy transportu
        // ani nie wystawi kont testowych poza loopback. Harness nie przyjmuje własnych argumentów użytkownika.
        List<String> fixed = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=8092",
            "--spring.profiles.active=test",
            "--ainv.ksef.enabled=false", "--ainv.ksef.inbound.enabled=false", "--ainv.sap-write.enabled=false",
            "--ainv.sap-write.sweeper.enabled=false", "--cds.security.mock.enabled=true"));
        if (uatXml) {
            fixed.addAll(List.of("--ainv.ksef.inbound.storage.hana.enabled=true",
                "--ainv.ksef.inbound.storage.hana.allow-local-h2=true", "--ainv.ksef.inbound.xml-download.enabled=true"));
        }
        var context = app.run(fixed.toArray(String[]::new));
        if (context.getBean(org.springframework.core.env.Environment.class).acceptsProfiles(
                org.springframework.core.env.Profiles.of("cf", "cloud", "prod", "production"))) {
            context.close();
            throw new IllegalStateException("INBOX_LOCAL_HARNESS_ONLY");
        }
        var runtime = context.getBean(com.sap.cds.services.runtime.CdsRuntime.class);
        int[] xmlDocuments = {0};
        runtime.requestContext().systemUser().run(ctx -> {
            var db = context.getBean(PersistenceService.class);
            InboundFixtures.seed(db);
            if (uatXml) {
                xmlDocuments[0] = InboundFixtures.seedXml(db,
                        context.getBean(customer.cap_java.ksef.storage.InboundDocumentStorage.class).store());
            }
        });
        System.out.println("INT-02 synthetic Inbox ready on loopback:8092; KSeF/SAP transports OFF"
                + (uatXml ? "; BETA-02 UAT synthetic XML documents=" + xmlDocuments[0] + " (local H2 only)" : ""));
    }
}
