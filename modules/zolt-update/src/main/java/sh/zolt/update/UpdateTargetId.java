package sh.zolt.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable opaque identity for one mutable or reportable dependency-version surface. */
public final class UpdateTargetId {
    private static final byte[] DOMAIN = "zolt-update-target\0".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ENCODED = Pattern.compile("^zt1_[A-Za-z0-9_-]{43}$");
    private static final String PREFIX = "zt1_";

    private final String value;

    private UpdateTargetId(String value) {
        this.value = value;
    }

    public static UpdateTargetId create(
            String manifestPath,
            OutdatedSurface surface,
            String section,
            String identifier) {
        String canonicalManifest = requireCanonicalPath(manifestPath, "manifest path");
        OutdatedSurface canonicalSurface = Objects.requireNonNull(surface, "surface");
        String canonicalSection = requireCanonicalText(section, "section");
        String canonicalIdentifier = requireCanonicalText(identifier, "identifier");
        MessageDigest digest = sha256();
        digest.update(DOMAIN);
        digest.update((byte) 1);
        updateField(digest, canonicalManifest);
        updateField(digest, canonicalSurface.jsonName());
        updateField(digest, canonicalSection);
        updateField(digest, canonicalIdentifier);
        return new UpdateTargetId(PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest()));
    }

    public static UpdateTargetId parse(String value) {
        if (value == null || !ENCODED.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Zolt update target ID.");
        }
        String encoded = value.substring(PREFIX.length());
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Zolt update target ID.", exception);
        }
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (decoded.length != 32 || !encoded.equals(canonical)) {
            throw new IllegalArgumentException("Invalid Zolt update target ID.");
        }
        return new UpdateTargetId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UpdateTargetId targetId && value.equals(targetId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    static String requireCanonicalPath(String value, String subject) {
        String canonical = requireCanonicalText(value, subject);
        if (canonical.startsWith("/") || canonical.contains("\\")) {
            throw new UpdateTargetIdentityException("Update target " + subject + " must be a relative POSIX path.");
        }
        String[] segments = canonical.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new UpdateTargetIdentityException("Update target " + subject + " must be normalized.");
            }
        }
        return canonical;
    }

    static String requireCanonicalText(String value, String subject) {
        if (value == null || value.isBlank()) {
            throw new UpdateTargetIdentityException("Update target " + subject + " is required.");
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new UpdateTargetIdentityException(
                    "Update target " + subject + " must use Unicode NFC normalization.");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new UpdateTargetIdentityException(
                            "Update target " + subject + " must contain valid Unicode.");
                }
                int codePoint = Character.toCodePoint(character, value.charAt(++index));
                if (Character.isISOControl(codePoint)) {
                    throw new UpdateTargetIdentityException("Update target " + subject + " cannot contain controls.");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new UpdateTargetIdentityException("Update target " + subject + " must contain valid Unicode.");
            } else if (Character.isISOControl(character)) {
                throw new UpdateTargetIdentityException("Update target " + subject + " cannot contain controls.");
            }
        }
        return value;
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
