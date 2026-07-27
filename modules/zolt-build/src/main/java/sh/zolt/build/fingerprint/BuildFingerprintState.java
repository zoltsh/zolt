package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record BuildFingerprintState(
        String fingerprintSha256,
        Map<Path, BuildFingerprintCachedFileHash> files) {
    private static final String VERSION = "1";

    static Optional<BuildFingerprintState> parse(List<String> lines) {
        if (lines.size() < 2 || !("version=" + VERSION).equals(lines.get(0))) {
            return Optional.empty();
        }
        String fingerprintSha256 = value(lines.get(1), "fingerprintSha256=");
        if (fingerprintSha256 == null || fingerprintSha256.isBlank()) {
            return Optional.empty();
        }
        Map<Path, BuildFingerprintCachedFileHash> files = new LinkedHashMap<>();
        for (int index = 2; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 5 || !"file".equals(parts[0])) {
                return Optional.empty();
            }
            try {
                Path path = Path.of(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                        .toAbsolutePath()
                        .normalize();
                long size = Long.parseLong(parts[2]);
                long lastModifiedNanos = Long.parseLong(parts[3]);
                files.put(path, new BuildFingerprintCachedFileHash(path, size, lastModifiedNanos, parts[4]));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return Optional.of(new BuildFingerprintState(fingerprintSha256, Map.copyOf(files)));
    }

    static String format(String fingerprint, Map<Path, BuildFingerprintCachedFileHash> files) {
        StringBuilder content = new StringBuilder();
        line(content, "version", VERSION);
        line(content, "fingerprintSha256", sha256(fingerprint.getBytes(StandardCharsets.UTF_8)));
        files.values().stream()
                .sorted(Comparator.comparing(cached -> cached.path().toString()))
                .forEach(cached -> content
                        .append("file")
                        .append('\t')
                        .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                                cached.path().toString().getBytes(StandardCharsets.UTF_8)))
                        .append('\t')
                        .append(cached.size())
                        .append('\t')
                        .append(cached.lastModifiedNanos())
                        .append('\t')
                        .append(cached.hash())
                        .append('\n'));
        return content.toString();
    }

    boolean matchesFingerprint(String fingerprint) {
        return fingerprintSha256.equals(sha256(fingerprint.getBytes(StandardCharsets.UTF_8)));
    }

    Optional<String> hashIfCurrent(Path path) {
        return fileIfCurrent(path).map(BuildFingerprintCachedFileHash::hash);
    }

    Optional<BuildFingerprintCachedFileHash> fileIfCurrent(Path path) {
        BuildFingerprintCachedFileHash file = files.get(path.toAbsolutePath().normalize());
        return file != null && file.isCurrent() ? Optional.of(file) : Optional.empty();
    }

    private static String value(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()) : null;
    }

    private static void line(StringBuilder content, String name, String value) {
        content.append(name).append('=').append(value).append('\n');
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute build fingerprint because SHA-256 is unavailable.", exception);
        }
    }
}
