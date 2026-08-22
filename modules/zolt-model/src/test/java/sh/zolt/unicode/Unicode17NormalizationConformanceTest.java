package sh.zolt.unicode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class Unicode17NormalizationConformanceTest {
    private static final byte[] MAGIC = {'Z', 'O', 'L', 'T', 'N', 'F', 'C', 0};

    @Test
    void passesCompleteUnicode17NfcConformanceCorpus() throws IOException {
        InputStream resource = getClass().getResourceAsStream("unicode-17.0.0-nfc-conformance.bin");
        assertNotNull(resource);
        try (resource;
                DataInputStream input = new DataInputStream(resource)) {
            assertArrayEquals(MAGIC, input.readNBytes(MAGIC.length));
            assertEquals(Unicode17DataIdentity.FORMAT_VERSION, input.readUnsignedShort());
            assertEquals(
                    Unicode17DataIdentity.NORMALIZATION_TEST_SHA256,
                    HexFormat.of().formatHex(input.readNBytes(32)));
            int count = input.readInt();
            assertEquals(37_671, count);
            for (int index = 0; index < count; index++) {
                String source = readScalars(input);
                String expected = readScalars(input);
                int vector = index;
                assertEquals(expected, Unicode17Portability.normalizeNfc(source), () -> failure(vector, source));
            }
            assertEquals(-1, input.read());
        }
    }

    private static String readScalars(DataInputStream input) throws IOException {
        int[] scalars = new int[input.readUnsignedShort()];
        for (int index = 0; index < scalars.length; index++) {
            scalars[index] = input.readInt();
        }
        return UnicodeScalarSequence.encode(scalars);
    }

    private static String failure(int index, String source) {
        StringBuilder message = new StringBuilder("NFC vector ").append(index).append(" failed for");
        for (int scalar : UnicodeScalarSequence.decode(source)) {
            message.append(String.format(" U+%04X", scalar));
        }
        return message.toString();
    }
}
