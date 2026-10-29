package customer.cap_java.ksef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/** Jawny magazyn lokalnego eksperymentu TEST; nie jest trwałym magazynem Cloud Foundry. */
@Component
public class LocalTestDocumentStore implements KsefDocumentStore {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final Path root;

    public LocalTestDocumentStore(@Value("${ainv.ksef.local-store:}") String directory) {
        if (!directory.isBlank() && System.getenv("VCAP_APPLICATION") != null) {
            throw KsefFailure.invalid("KSEF_LOCAL_STORE_FORBIDDEN_ON_CF");
        }
        root = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    /** Hash w nazwie i atomowy rename: restart nie udostępni częściowego dokumentu. */
    @Override public String put(byte[] bytes) {
        requireConfigured();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw KsefFailure.invalid("KSEF_DOCUMENT_SIZE_INVALID");
        }
        String reference = KsefInvoiceSnapshotRegistry.sha256Hex(bytes) + ".xml";
        try {
            Files.createDirectories(root);
            Path target = resolve(reference);
            if (Files.exists(target)) {
                if (!java.util.Arrays.equals(get(reference), bytes)) {
                    throw KsefFailure.invalid("KSEF_DOCUMENT_HASH_MISMATCH");
                }
                return reference;
            }
            Path temporary = Files.createTempFile(root, ".write-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            return reference;
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.invalid("KSEF_DOCUMENT_STORE_FAILED"); }
    }

    /** Walidacja referencji i SHA-256 chroni przed traversal i podmianą bajtów po przygotowaniu. */
    @Override public byte[] get(String reference) {
        requireConfigured();
        try {
            Path path = resolve(reference);
            if (Files.size(path) > MAX_BYTES) { throw KsefFailure.invalid("KSEF_DOCUMENT_SIZE_INVALID"); }
            byte[] bytes = Files.readAllBytes(path);
            if (!(KsefInvoiceSnapshotRegistry.sha256Hex(bytes) + ".xml").equals(reference)) {
                throw KsefFailure.invalid("KSEF_DOCUMENT_HASH_MISMATCH");
            }
            return bytes;
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.invalid("KSEF_DOCUMENT_READ_FAILED"); }
    }

    private Path resolve(String reference) {
        if (reference == null || !reference.matches("[a-f0-9]{64}\\.xml")) {
            throw KsefFailure.invalid("KSEF_DOCUMENT_REFERENCE_INVALID");
        }
        Path target = root.resolve(reference).normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(target) || Files.isSymbolicLink(root)) {
            throw KsefFailure.invalid("KSEF_DOCUMENT_REFERENCE_INVALID");
        }
        return target;
    }

    private void requireConfigured() {
        if (root == null) { throw KsefFailure.invalid("KSEF_DOCUMENT_STORE_NOT_CONFIGURED"); }
    }
}
