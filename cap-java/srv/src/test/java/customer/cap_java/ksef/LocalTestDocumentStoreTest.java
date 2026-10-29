package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalTestDocumentStoreTest {
    @TempDir Path directory;

    @Test void immutableExactBytesAndTamperDetection() throws Exception {
        var store = new LocalTestDocumentStore(directory.toString());
        byte[] bytes = new byte[]{10, 13, 0, 1};
        String reference = store.put(bytes);
        assertEquals(reference, store.put(bytes));
        assertArrayEquals(bytes, store.get(reference));
        Files.write(directory.resolve(reference), new byte[]{2});
        assertThrows(KsefFailure.class, () -> store.get(reference));
    }

    @Test void noImplicitStorageAndNoTraversal() {
        assertThrows(KsefFailure.class, () -> new LocalTestDocumentStore("").put(new byte[]{1}));
        var store = new LocalTestDocumentStore(directory.toString());
        assertThrows(KsefFailure.class, () -> store.get("../secret.xml"));
        assertThrows(KsefFailure.class, () -> store.put(new byte[0]));
    }
}
