package sh.zolt.workspace.unicode;

import sh.zolt.workspace.unicode.UnicodeScalarSequence.IntBuffer;

/** Unicode-version-pinned normalization and comparison keys for logical workspace paths. */
public final class Unicode17Portability {
    private static final Unicode17Tables TABLES = Unicode17Tables.load();
    private static final Unicode17Normalization NORMALIZATION = new Unicode17Normalization(TABLES);

    private Unicode17Portability() {}

    /** Returns Unicode 17.0.0 NFC after validating that {@code value} is a scalar sequence. */
    public static String normalizeNfc(String value) {
        return NORMALIZATION.nfc(value);
    }

    /**
     * Returns NFC, followed by full default C/F case folding, followed by NFC again.
     *
     * <p>The result is a comparison key only. Callers retain {@link #normalizeNfc(String)} for the
     * canonical path with its actual directory-entry casing.
     */
    public static String key(String value) {
        int[] normalized = UnicodeScalarSequence.decode(NORMALIZATION.nfc(value));
        IntBuffer folded = new IntBuffer(normalized.length);
        for (int scalar : normalized) {
            int[] mapping = TABLES.caseFold(scalar);
            if (mapping == null) {
                folded.add(scalar);
            } else {
                for (int mapped : mapping) {
                    folded.add(mapped);
                }
            }
        }
        return UnicodeScalarSequence.encode(NORMALIZATION.nfc(folded.toArray()));
    }
}
