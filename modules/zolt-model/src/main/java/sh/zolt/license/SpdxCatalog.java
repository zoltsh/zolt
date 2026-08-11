package sh.zolt.license;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Source-pinned SPDX License List identifiers used without runtime network access. */
public final class SpdxCatalog {
    public static final String VERSION = "3.28.0";
    private static final String LICENSES = "/sh/zolt/license/spdx-license-ids.txt";
    private static final String EXCEPTIONS = "/sh/zolt/license/spdx-exception-ids.txt";
    private static final String DEPRECATED = "/sh/zolt/license/spdx-deprecated-license-ids.txt";
    private static final String REPLACEMENTS = "/sh/zolt/license/spdx-deprecated-license-replacements.txt";
    private static final SpdxCatalog DEFAULT = new SpdxCatalog(
            load(LICENSES),
            load(EXCEPTIONS),
            load(DEPRECATED).keySet(),
            loadReplacements(REPLACEMENTS));

    private final Map<String, String> licenses;
    private final Map<String, String> exceptions;
    private final Set<String> deprecated;
    private final Map<String, String> replacements;

    SpdxCatalog(Map<String, String> licenses, Map<String, String> exceptions) {
        this(licenses, exceptions, Set.of(), Map.of());
    }

    private SpdxCatalog(
            Map<String, String> licenses,
            Map<String, String> exceptions,
            Set<String> deprecated,
            Map<String, String> replacements) {
        this.licenses = Map.copyOf(licenses);
        this.exceptions = Map.copyOf(exceptions);
        this.deprecated = Set.copyOf(deprecated);
        this.replacements = Map.copyOf(replacements);
    }

    public static SpdxCatalog defaultCatalog() {
        return DEFAULT;
    }

    public Optional<String> canonicalLicense(String candidate) {
        String normalized = normalize(candidate);
        return deprecated.contains(normalized)
                ? Optional.empty()
                : Optional.ofNullable(licenses.get(normalized));
    }

    public Optional<String> knownLicense(String candidate) {
        return Optional.ofNullable(licenses.get(normalize(candidate)));
    }

    public boolean isDeprecatedLicense(String candidate) {
        return deprecated.contains(normalize(candidate));
    }

    public Optional<String> deprecatedReplacement(String candidate) {
        return Optional.ofNullable(replacements.get(normalize(candidate)));
    }

    public List<String> licenseIds() {
        return licenses.entrySet().stream()
                .filter(entry -> !deprecated.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted()
                .toList();
    }

    List<String> knownLicenseIds() {
        return licenses.values().stream().sorted().toList();
    }

    public Optional<String> canonicalException(String candidate) {
        return Optional.ofNullable(exceptions.get(normalize(candidate)));
    }

    List<String> exceptionIds() {
        return exceptions.values().stream().sorted().toList();
    }

    public int licenseCount() {
        return licenses.size();
    }

    public int exceptionCount() {
        return exceptions.size();
    }

    public int deprecatedLicenseCount() {
        return deprecated.size();
    }

    private static Map<String, String> load(String resource) {
        InputStream stream = SpdxCatalog.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled SPDX catalog resource " + resource + ".");
        }
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                String previous = values.put(normalize(value), value);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate SPDX catalog identifier " + value + " in " + resource + ".");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled SPDX catalog resource " + resource + ".", exception);
        }
        return values;
    }

    private static Map<String, String> loadReplacements(String resource) {
        InputStream stream = SpdxCatalog.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled SPDX replacement resource " + resource + ".");
        }
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                int separator = value.indexOf('=');
                if (separator <= 0 || separator + 1 == value.length()) {
                    throw new IllegalStateException("Invalid SPDX replacement entry " + value + " in " + resource + ".");
                }
                String deprecatedId = normalize(value.substring(0, separator));
                String replacement = value.substring(separator + 1).trim();
                String previous = values.put(deprecatedId, replacement);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate SPDX replacement identifier " + deprecatedId + " in " + resource + ".");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled SPDX replacement resource " + resource + ".", exception);
        }
        return values;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
