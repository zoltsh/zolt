package sh.zolt.manifest;

import java.util.Objects;

final class ProjectIdentityValue {
    private ProjectIdentityValue() {
    }

    static String validate(String value, String subject) {
        Objects.requireNonNull(value, subject + " must not be null.");
        if (value.isEmpty()) {
            throw invalid(subject, value);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '.'
                    || character == '-';
            if (!accepted) {
                throw invalid(subject, value);
            }
        }
        return value;
    }

    private static IllegalArgumentException invalid(String subject, String value) {
        return new IllegalArgumentException(
                "Invalid " + subject + " `" + value + "`: use only ASCII letters, digits, `_`, `.`, and `-`.");
    }
}
