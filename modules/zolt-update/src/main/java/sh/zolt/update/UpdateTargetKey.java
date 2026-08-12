package sh.zolt.update;

import java.util.Objects;

/** Raw structural identity used by legacy reporting and policy planning without schema-v2 NFC rules. */
public record UpdateTargetKey(
        String manifestPath,
        OutdatedSurface surface,
        String section,
        String identifier) {
    public UpdateTargetKey {
        manifestPath = requirePath(manifestPath, "manifest path");
        surface = Objects.requireNonNull(surface, "surface");
        section = requireText(section, "section");
        identifier = requireText(identifier, "identifier");
    }

    static String requirePath(String value, String subject) {
        String path = requireText(value, subject);
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("Update " + subject + " must be relative.");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Update " + subject + " must be normalized.");
            }
        }
        return path;
    }

    private static String requireText(String value, String subject) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Update " + subject + " is required.");
        }
        for (int index = 0; index < value.length(); index++) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("Update " + subject + " cannot contain controls.");
            }
            if (Character.charCount(codePoint) == 2) {
                index++;
            } else if (Character.isSurrogate(value.charAt(index))) {
                throw new IllegalArgumentException("Update " + subject + " must contain valid Unicode.");
            }
        }
        return value;
    }
}
