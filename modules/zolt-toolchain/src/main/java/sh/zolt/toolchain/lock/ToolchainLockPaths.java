package sh.zolt.toolchain.lock;

import sh.zolt.lockfile.CacheRelativePath;
import java.util.regex.Pattern;

final class ToolchainLockPaths {
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    private ToolchainLockPaths() {
    }

    static String component(String value, String field) {
        if (value == null || !COMPONENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Java toolchain " + field + " must be a safe path component.");
        }
        return value;
    }

    static String layout(String value, String field, boolean allowRoot) {
        if (allowRoot && value.equals(".")) {
            return value;
        }
        try {
            new CacheRelativePath(value);
            for (String segment : value.split("/")) {
                if (segment.indexOf(':') >= 0 || segment.endsWith(".") || segment.endsWith(" ")) {
                    throw new IllegalArgumentException("nonportable path component");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Java toolchain " + field + " must be a confined relative path using '/' separators.", exception);
        }
        return value;
    }
}
