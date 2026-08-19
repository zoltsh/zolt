package sh.zolt.workspace.unicode;

import java.util.Arrays;
import java.util.Objects;

final class UnicodeScalarSequence {
    private UnicodeScalarSequence() {}

    static int[] decode(String value) {
        Objects.requireNonNull(value, "value");
        IntBuffer scalars = new IntBuffer(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= 0xD800 && current <= 0xDBFF) {
                if (index + 1 >= value.length()) {
                    throw invalid(index);
                }
                char following = value.charAt(++index);
                if (following < 0xDC00 || following > 0xDFFF) {
                    throw invalid(index - 1);
                }
                scalars.add(0x10000 + ((current - 0xD800) << 10) + (following - 0xDC00));
            } else if (current >= 0xDC00 && current <= 0xDFFF) {
                throw invalid(index);
            } else {
                scalars.add(current);
            }
        }
        return scalars.toArray();
    }

    static String encode(int[] scalars) {
        StringBuilder value = new StringBuilder(scalars.length);
        for (int scalar : scalars) {
            if (scalar < 0 || scalar > 0x10FFFF || scalar >= 0xD800 && scalar <= 0xDFFF) {
                throw new IllegalArgumentException("Invalid Unicode scalar value U+" + Integer.toHexString(scalar));
            }
            if (scalar <= 0xFFFF) {
                value.append((char) scalar);
            } else {
                int supplementary = scalar - 0x10000;
                value.append((char) (0xD800 + (supplementary >>> 10)));
                value.append((char) (0xDC00 + (supplementary & 0x3FF)));
            }
        }
        return value.toString();
    }

    private static IllegalArgumentException invalid(int index) {
        return new IllegalArgumentException(
                "Value is not a Unicode scalar sequence: unpaired surrogate at UTF-16 index " + index + ".");
    }

    static final class IntBuffer {
        private int[] values;
        private int size;

        IntBuffer(int capacity) {
            values = new int[Math.max(8, capacity)];
        }

        void add(int value) {
            ensureCapacity();
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        void set(int index, int value) {
            values[index] = value;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }

        private void ensureCapacity() {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
        }
    }
}
