package customer.cap_java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.services.cds.CdsUpdateEventContext;
import com.sap.cds.services.persistence.PersistenceService;

import org.springframework.stereotype.Component;

import com.sap.cds.CdsData;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;

@Component
@ServiceName("BlobService")
public class BlobDocumentsHandler implements EventHandler {

    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private final PersistenceService db;

    public BlobDocumentsHandler(PersistenceService db) {
        this.db = db;
    }

    /** Waliduje metadane POST; błędne wejście zatrzymuje zapis w CAP. */
    @Before(
        event = CqnService.EVENT_CREATE,
        entity = "BlobService.Documents"
    )
    public void validateCreate(List<CdsData> documents) {
        for (CdsData document : documents) {
            String fileName = (String) document.get("fileName");
            String mimeType = (String) document.get("mimeType");

            validateFileName(fileName);
            validateMediaType(fileName, mimeType);

            // POST tworzy wyłącznie metadane; binarny content trafia do osobnego PUT.
            if (document.get("content") != null) {
                throw invalidMetadata();
            }
        }
    }

    /** Ogranicza rozmiar strumienia i zapisuje SHA-256 dokładnie z pobranych bajtów. */
    @Before(
        event = CqnService.EVENT_UPDATE,
        entity = "BlobService.Documents"
    )
    public void validateContent(CdsUpdateEventContext context, List<CdsData> documents) {
        // Akceptujemy wyłącznie pojedynczy dokument właściciela, także poza zwykłym URL OData.
        Object id = CqnAnalyzer.create(context.getModel()).analyze(context.getCqn()).targetKeys().get("ID");
        if (id == null || documents.size() != 1) { throw invalidMetadata(); }
        var existing = db.run(Select.from("customer.cap_java.KsefDocuments")
            .columns("fileName", "mimeType")
            .where(d -> d.get("ID").eq(id).and(d.get("createdBy").eq(context.getUserInfo().getName()))))
            .first().orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "BLOB_NOT_FOUND"));
        String storedMime = (String) existing.get("mimeType");
        for (CdsData document : documents) {
            Object value = document.get("content");
            if (value == null || document.containsKey("fileName")
                    || (document.containsKey("mimeType") && !storedMime.equals(document.get("mimeType")))) {
                throw invalidMetadata();
            }
            if (!(value instanceof InputStream input)) {
                throw invalidMetadata();
            }

            try (input) {
                byte[] bytes = input.readNBytes(MAX_CONTENT_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_CONTENT_BYTES) {
                    throw invalidMetadata();
                }

                validateFileBytes(bytes, storedMime);

                document.put("content", new ByteArrayInputStream(bytes));
                document.put("sha256", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                ));
            } catch (IOException e) {
                throw invalidMetadata();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }

    /** XML musi być poprawny i bez DTD/XXE; PDF sprawdzamy podstawową sygnaturą, bez renderowania. */
    private static void validateFileBytes(byte[] bytes, String mimeType) {
        if ("application/pdf".equals(mimeType)) {
            String pdf = new String(bytes, StandardCharsets.ISO_8859_1);
            if (!pdf.startsWith("%PDF-") || !pdf.stripTrailing().endsWith("%%EOF")) { throw invalidMetadata(); }
            return;
        }
        try {
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> { throw invalidMetadata(); });
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            int depth = 0;
            int events = 0;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (++events > 100000 || event == XMLStreamConstants.DTD
                            || event == XMLStreamConstants.ENTITY_REFERENCE) { throw invalidMetadata(); }
                    if (event == XMLStreamConstants.START_ELEMENT && ++depth > 128) { throw invalidMetadata(); }
                    if (event == XMLStreamConstants.END_ELEMENT) { depth--; }
                }
            } finally { reader.close(); }
        } catch (ServiceException e) { throw e; }
        catch (Exception e) { throw invalidMetadata(); }
    }

    /** Odrzuca puste, zbyt długie i niebezpieczne nazwy; nazwa nie jest ścieżką pliku na serwerze. */
    private static void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_LENGTH
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw invalidMetadata();
        }

        int extensionSeparator = fileName.lastIndexOf('.');
        if (extensionSeparator <= 0) {
            throw invalidMetadata();
        }
        String extension = fileName.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("xml") && !extension.equals("pdf")) {
            throw invalidMetadata();
        }
    }

    /** MIME musi zgadzać się z rozszerzeniem; nie zapisujemy nieoczekiwanych typów mediów. */
    private static void validateMediaType(String fileName, String mimeType) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        boolean valid = (extension.equals("xml") && "application/xml".equals(mimeType))
                || (extension.equals("pdf") && "application/pdf".equals(mimeType));
        if (!valid) {
            throw invalidMetadata();
        }
    }

    private static ServiceException invalidMetadata() {
        return new ServiceException(ErrorStatuses.BAD_REQUEST, "BLOB_INVALID_METADATA");
    }
}
