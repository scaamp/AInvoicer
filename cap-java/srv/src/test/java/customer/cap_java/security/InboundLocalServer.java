package customer.cap_java.security;

import org.springframework.boot.SpringApplication;
import com.sap.cds.services.persistence.PersistenceService;

/** Testowy serwer do UI5 → prawdziwe OData/H2; klasa i fixture nie trafiają do produkcyjnego JAR. */
public final class InboundLocalServer {
    public static void main(String[] args) {
        if (System.getenv("VCAP_APPLICATION") != null || System.getenv("VCAP_SERVICES") != null || args.length != 0) {
            throw new IllegalStateException("INBOX_LOCAL_HARNESS_ONLY");
        }
        var app = new SpringApplication(customer.cap_java.Application.class);
        app.setAdditionalProfiles("test");
        // Argumenty CLI mają pierwszeństwo przed środowiskiem: żaden lokalny env nie włączy transportu
        // ani nie wystawi kont testowych poza loopback. Harness nie przyjmuje własnych argumentów użytkownika.
        var context = app.run("--server.address=127.0.0.1", "--server.port=8092", "--spring.profiles.active=test",
            "--ainv.ksef.enabled=false", "--ainv.ksef.inbound.enabled=false", "--ainv.sap-write.enabled=false",
            "--ainv.sap-write.sweeper.enabled=false", "--cds.security.mock.enabled=true");
        if (context.getBean(org.springframework.core.env.Environment.class).acceptsProfiles(
                org.springframework.core.env.Profiles.of("cf", "cloud", "prod", "production"))) {
            context.close();
            throw new IllegalStateException("INBOX_LOCAL_HARNESS_ONLY");
        }
        var runtime = context.getBean(com.sap.cds.services.runtime.CdsRuntime.class);
        runtime.requestContext().systemUser().run(ctx -> {
            InboundFixtures.seed(context.getBean(PersistenceService.class));
        });
        System.out.println("INT-02 synthetic Inbox ready on loopback:8092; KSeF/SAP transports OFF");
    }
}
