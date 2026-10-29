package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import pl.akmf.ksef.sdk.api.services.DefaultCertificateService;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.client.model.auth.TokenInfo;
import pl.akmf.ksef.sdk.client.model.certificate.publickey.PublicKeyCertificate;
import pl.akmf.ksef.sdk.client.model.certificate.publickey.PublicKeyCertificateUsage;

/** Prawdziwy HTTP klient SDK + kryptografia MF; lokalny serwer zastępuje KSeF. */
class KsefSdkHttpContractTest {
    @Test void formCodeEncryptionExactBytesAndAuthorizationMatchOfficialContract() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new ObjectMapper();
        var openBody = new AtomicReference<String>();
        var sendBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var cert = new DefaultCertificateService().getCompanySeal("TEST", "VATPL-1234563218", "TEST");
        var key = new PublicKeyCertificate();
        key.setCertificate(Base64.getEncoder().encodeToString(cert.certificate().getEncoded()));
        key.setValidFrom(OffsetDateTime.now().minusDays(1));
        key.setValidTo(OffsetDateTime.now().plusDays(1));
        key.setUsage(List.of(PublicKeyCertificateUsage.SYMMETRICKEYENCRYPTION, PublicKeyCertificateUsage.KSEFTOKENENCRYPTION));
        key.setPublicKeyId("test-public-key");
        // Mapper SDK serializuje daty ISO; wykorzystujemy go także do odpowiedzi lokalnego serwera.
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        byte[] keys = mapper.writeValueAsBytes(List.of(key));
        server.createContext("/v2/security/public-key-certificates", e -> {
            e.sendResponseHeaders(200, keys.length); e.getResponseBody().write(keys); e.close();
        });
        server.createContext("/v2/sessions/online", e -> {
            String path = e.getRequestURI().getPath();
            authorization.set(e.getRequestHeaders().getFirst("Authorization"));
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.endsWith("/invoices")) { sendBody.set(body); }
            else { openBody.set(body); }
            byte[] response = (path.endsWith("/invoices") ? "{\"referenceNumber\":\"invoice-ref\"}"
                    : "{\"referenceNumber\":\"session-ref\",\"validUntil\":\"2026-10-29T20:00:00Z\"}")
                    .getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(path.endsWith("/invoices") ? 202 : 201, response.length);
            e.getResponseBody().write(response); e.close();
        });
        server.start();
        try {
            var client = KsefSdkConfiguration.createClient("http://127.0.0.1:" + server.getAddress().getPort());
            var crypto = new DefaultCryptographyService(client);
            var adapter = new KsefOnlineSession(client, crypto);
            var encryption = adapter.encryption();
            var another = adapter.encryption();
            assertFalse(java.util.Arrays.equals(encryption.cipherKey(), another.cipherKey()));
            assertFalse(java.util.Arrays.equals(encryption.cipherIv(), another.cipherIv()));
            var tokens = new KsefAuthentication.Tokens(new TokenInfo("mock-jwt", OffsetDateTime.now().plusHours(1)), null);
            assertEquals("session-ref", adapter.open(encryption, tokens));
            byte[] xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><fixture>Zażółć</fixture>\r\n".getBytes(StandardCharsets.UTF_8);
            assertEquals("invoice-ref", adapter.send("session-ref", xml, encryption, tokens));
            assertEquals("Bearer mock-jwt", authorization.get());
            var form = mapper.readTree(openBody.get()).get("formCode");
            assertEquals("FA (3)", form.get("systemCode").asText());
            assertEquals("1-0E", form.get("schemaVersion").asText());
            assertEquals("FA", form.get("value").asText());
            var body = mapper.readTree(sendBody.get());
            assertEquals(crypto.getMetaData(xml).getHashSHA(), body.get("invoiceHash").asText());
            assertEquals(xml.length, body.get("invoiceSize").asInt());
            byte[] ciphertext = Base64.getDecoder().decode(body.get("encryptedInvoiceContent").asText());
            assertEquals(crypto.getMetaData(ciphertext).getHashSHA(), body.get("encryptedInvoiceHash").asText());
            var decipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            decipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryption.cipherKey(), "AES"), new IvParameterSpec(encryption.cipherIv()));
            assertArrayEquals(xml, decipher.doFinal(ciphertext));
        } finally { server.stop(0); }
    }

    @Test void post500IsNotAutomaticallyRetriedAndRetryAfterIsRetained() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/v2/sessions/online/session-ref/close", e -> {
            calls.incrementAndGet();
            e.getResponseHeaders().add("Retry-After", "120");
            byte[] body = "{\"exception\":{\"exceptionDetailList\":[]}}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(500, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
        try {
            var client = KsefSdkConfiguration.createClient("http://127.0.0.1:" + server.getAddress().getPort());
            var adapter = new KsefOnlineSession(client, mock(DefaultCryptographyService.class));
            var tokens = new KsefAuthentication.Tokens(new TokenInfo("mock-jwt", OffsetDateTime.now().plusHours(1)), null);
            var error = assertThrows(KsefFailure.class, () -> adapter.close("session-ref", tokens));
            assertEquals(1, calls.get());
            assertTrue(error.ambiguous());
            assertFalse(error.retryable());
            assertNotNull(error.retryNotBefore());
            assertNull(error.getCause());
        } finally { server.stop(0); }
    }
}
