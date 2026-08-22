package sh.zolt.unicode;

import sh.zolt.unicode.UnicodeScalarSequence.IntBuffer;

final class Unicode17Normalization {
    private static final int S_BASE = 0xAC00;
    private static final int L_BASE = 0x1100;
    private static final int V_BASE = 0x1161;
    private static final int T_BASE = 0x11A7;
    private static final int L_COUNT = 19;
    private static final int V_COUNT = 21;
    private static final int T_COUNT = 28;
    private static final int N_COUNT = V_COUNT * T_COUNT;
    private static final int S_COUNT = L_COUNT * N_COUNT;

    private final Unicode17Tables tables;

    Unicode17Normalization(Unicode17Tables tables) {
        this.tables = tables;
    }

    String nfc(String value) {
        return UnicodeScalarSequence.encode(nfc(UnicodeScalarSequence.decode(value)));
    }

    int[] nfc(int[] scalars) {
        IntBuffer decomposed = new IntBuffer(scalars.length);
        for (int scalar : scalars) {
            decompose(scalar, decomposed);
        }
        return compose(decomposed);
    }

    private void decompose(int scalar, IntBuffer output) {
        if (scalar >= S_BASE && scalar < S_BASE + S_COUNT) {
            int syllableIndex = scalar - S_BASE;
            appendOrdered(L_BASE + syllableIndex / N_COUNT, output);
            appendOrdered(V_BASE + syllableIndex % N_COUNT / T_COUNT, output);
            int trailingIndex = syllableIndex % T_COUNT;
            if (trailingIndex != 0) {
                appendOrdered(T_BASE + trailingIndex, output);
            }
            return;
        }
        int[] mapping = tables.decomposition(scalar);
        if (mapping == null) {
            appendOrdered(scalar, output);
            return;
        }
        for (int mapped : mapping) {
            decompose(mapped, output);
        }
    }

    private void appendOrdered(int scalar, IntBuffer output) {
        int combiningClass = tables.combiningClass(scalar);
        int insertion = output.size();
        output.add(scalar);
        if (combiningClass == 0) {
            return;
        }
        while (insertion > 0) {
            int previous = output.get(insertion - 1);
            if (tables.combiningClass(previous) <= combiningClass) {
                break;
            }
            output.set(insertion, previous);
            insertion--;
        }
        output.set(insertion, scalar);
    }

    private int[] compose(IntBuffer decomposed) {
        IntBuffer output = new IntBuffer(decomposed.size());
        int starterIndex = -1;
        int starter = -1;
        int lastCombiningClass = 0;
        for (int index = 0; index < decomposed.size(); index++) {
            int scalar = decomposed.get(index);
            int combiningClass = tables.combiningClass(scalar);
            int composite = starterIndex < 0 ? -1 : compose(starter, scalar);
            if (composite >= 0 && (lastCombiningClass == 0 || lastCombiningClass < combiningClass)) {
                output.set(starterIndex, composite);
                starter = composite;
                continue;
            }
            if (combiningClass == 0) {
                starterIndex = output.size();
                starter = scalar;
            }
            output.add(scalar);
            lastCombiningClass = combiningClass;
        }
        return output.toArray();
    }

    private int compose(int first, int second) {
        if (first >= L_BASE && first < L_BASE + L_COUNT && second >= V_BASE && second < V_BASE + V_COUNT) {
            return S_BASE + (first - L_BASE) * N_COUNT + (second - V_BASE) * T_COUNT;
        }
        if (first >= S_BASE
                && first < S_BASE + S_COUNT
                && (first - S_BASE) % T_COUNT == 0
                && second > T_BASE
                && second < T_BASE + T_COUNT) {
            return first + second - T_BASE;
        }
        return tables.composition(first, second);
    }
}
