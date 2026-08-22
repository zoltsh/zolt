package sh.zolt.unicode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

final class Unicode17Tables {
    private static final String RESOURCE = "unicode-17.0.0-portability.bin";
    private static final byte[] MAGIC = {'Z', 'O', 'L', 'T', 'U', '1', '7', 0};
    private static final int MAX_TABLE_ENTRIES = 100_000;
    private static final int CODE_POINT_BITS = 21;

    private final int[] decompositionCodePoints;
    private final int[][] decompositions;
    private final int[] combiningCodePoints;
    private final byte[] combiningClasses;
    private final long[] compositionPairs;
    private final int[] compositions;
    private final int[] foldCodePoints;
    private final int[][] folds;

    private Unicode17Tables(
            SequenceTable decompositions,
            int[] combiningCodePoints,
            byte[] combiningClasses,
            long[] compositionPairs,
            int[] compositions,
            SequenceTable folds) {
        this.decompositionCodePoints = decompositions.codePoints();
        this.decompositions = decompositions.mappings();
        this.combiningCodePoints = combiningCodePoints;
        this.combiningClasses = combiningClasses;
        this.compositionPairs = compositionPairs;
        this.compositions = compositions;
        this.foldCodePoints = folds.codePoints();
        this.folds = folds.mappings();
    }

    static Unicode17Tables load() {
        byte[] content = resource();
        require(
                sha256(content).equals(Unicode17DataIdentity.GENERATED_TABLE_SHA256),
                "Unicode 17 table checksum does not match its frozen identity.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(content))) {
            require(
                    Arrays.equals(input.readNBytes(MAGIC.length), MAGIC),
                    "Unicode 17 table has an invalid magic header.");
            require(
                    input.readUnsignedShort() == Unicode17DataIdentity.FORMAT_VERSION,
                    "Unicode 17 table has an unsupported format version.");
            require(
                    readString(input).equals(Unicode17DataIdentity.IDENTITY),
                    "Unicode 17 table has an unexpected data identity.");
            requireDigest(input, Unicode17DataIdentity.UNICODE_DATA_SHA256, "UnicodeData.txt");
            requireDigest(input, Unicode17DataIdentity.COMPOSITION_EXCLUSIONS_SHA256, "CompositionExclusions.txt");
            requireDigest(input, Unicode17DataIdentity.CASE_FOLDING_SHA256, "CaseFolding.txt");

            SequenceTable decompositions = readSequences(input, "decomposition");
            int combiningCount = readCount(input, "combining class");
            int[] combiningCodePoints = new int[combiningCount];
            byte[] combiningClasses = new byte[combiningCount];
            int previousCodePoint = -1;
            for (int index = 0; index < combiningCount; index++) {
                int codePoint = readScalar(input);
                require(codePoint > previousCodePoint, "Unicode 17 combining-class entries are not ordered.");
                int combiningClass = input.readUnsignedByte();
                require(combiningClass > 0, "Unicode 17 table contains a zero combining-class entry.");
                combiningCodePoints[index] = codePoint;
                combiningClasses[index] = (byte) combiningClass;
                previousCodePoint = codePoint;
            }

            int compositionCount = readCount(input, "composition");
            long[] compositionPairs = new long[compositionCount];
            int[] compositions = new int[compositionCount];
            long previousPair = -1;
            for (int index = 0; index < compositionCount; index++) {
                long pair = pair(readScalar(input), readScalar(input));
                require(pair > previousPair, "Unicode 17 composition entries are not ordered.");
                compositionPairs[index] = pair;
                compositions[index] = readScalar(input);
                previousPair = pair;
            }
            SequenceTable folds = readSequences(input, "case-fold");
            require(input.read() == -1, "Unicode 17 table contains trailing data.");
            return new Unicode17Tables(
                    decompositions,
                    combiningCodePoints,
                    combiningClasses,
                    compositionPairs,
                    compositions,
                    folds);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load the frozen Unicode 17 portability table.", exception);
        }
    }

    int[] decomposition(int codePoint) {
        int index = Arrays.binarySearch(decompositionCodePoints, codePoint);
        return index < 0 ? null : decompositions[index];
    }

    int combiningClass(int codePoint) {
        int index = Arrays.binarySearch(combiningCodePoints, codePoint);
        return index < 0 ? 0 : Byte.toUnsignedInt(combiningClasses[index]);
    }

    int composition(int first, int second) {
        int index = Arrays.binarySearch(compositionPairs, pair(first, second));
        return index < 0 ? -1 : compositions[index];
    }

    int[] caseFold(int codePoint) {
        int index = Arrays.binarySearch(foldCodePoints, codePoint);
        return index < 0 ? null : folds[index];
    }

    private static byte[] resource() {
        try (InputStream input = Unicode17Tables.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing frozen Unicode 17 portability table resource " + RESOURCE + ".");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read frozen Unicode 17 portability table resource.", exception);
        }
    }

    private static SequenceTable readSequences(DataInputStream input, String subject) throws IOException {
        int count = readCount(input, subject);
        int[] codePoints = new int[count];
        int[][] mappings = new int[count][];
        int previous = -1;
        for (int index = 0; index < count; index++) {
            int codePoint = readScalar(input);
            require(codePoint > previous, "Unicode 17 " + subject + " entries are not ordered.");
            int length = input.readUnsignedByte();
            require(length > 0, "Unicode 17 " + subject + " mapping is empty.");
            int[] mapping = new int[length];
            for (int mappingIndex = 0; mappingIndex < length; mappingIndex++) {
                mapping[mappingIndex] = readScalar(input);
            }
            codePoints[index] = codePoint;
            mappings[index] = mapping;
            previous = codePoint;
        }
        return new SequenceTable(codePoints, mappings);
    }

    private static int readCount(DataInputStream input, String subject) throws IOException {
        int count = input.readInt();
        require(count >= 0 && count <= MAX_TABLE_ENTRIES, "Unicode 17 " + subject + " count is invalid.");
        return count;
    }

    private static int readScalar(DataInputStream input) throws IOException {
        int value = input.readInt();
        require(value >= 0 && value <= 0x10FFFF && (value < 0xD800 || value > 0xDFFF),
                "Unicode 17 table contains a non-scalar value.");
        return value;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void requireDigest(DataInputStream input, String expected, String source) throws IOException {
        String actual = HexFormat.of().formatHex(input.readNBytes(32));
        require(actual.equals(expected), "Unicode 17 table records an unexpected " + source + " checksum.");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required to verify Unicode portability data.", exception);
        }
    }

    private static long pair(int first, int second) {
        return (long) first << CODE_POINT_BITS | second;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record SequenceTable(int[] codePoints, int[][] mappings) {}
}
