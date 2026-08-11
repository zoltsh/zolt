package sh.zolt.license;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Source-pinned SPDX License List identifiers used without runtime network access. */
public final class SpdxCatalog {
    public static final String VERSION = "3.28.0";
    private static final String LICENSES = "/sh/zolt/license/spdx-license-ids.txt";
    private static final String EXCEPTIONS = "/sh/zolt/license/spdx-exception-ids.txt";
    private static final SpdxCatalog DEFAULT = new SpdxCatalog(load(LICENSES), load(EXCEPTIONS));

    private final Map<String, String> licenses;
    private final Map<String, String> exceptions;

    SpdxCatalog(Map<String, String> licenses, Map<String, String> exceptions) {
        this.licenses = Map.copyOf(licenses);
        this.exceptions = Map.copyOf(exceptions);
    }

    public static SpdxCatalog defaultCatalog() {
        return DEFAULT;
    }

    public Optional<String> canonicalLicense(String candidate) {
        return Optional.ofNullable(licenses.get(normalize(candidate)));
    }

    public Optional<String> canonicalException(String candidate) {
        return Optional.ofNullable(exceptions.get(normalize(candidate)));
    }

    public int licenseCount() {
        return licenses.size();
    }

    public int exceptionCount() {
        return exceptions.size();
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
