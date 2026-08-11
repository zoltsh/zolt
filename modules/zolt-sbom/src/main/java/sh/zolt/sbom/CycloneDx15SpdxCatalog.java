package sh.zolt.sbom;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import sh.zolt.license.SpdxCatalog;

/** SPDX identifiers accepted by the parser but absent from CycloneDX 1.5's pinned SPDX enum. */
final class CycloneDx15SpdxCatalog {
    private static final String UNSUPPORTED = "/sh/zolt/sbom/cyclonedx-1.5-unsupported-spdx-ids.txt";
    private static final Set<String> UNSUPPORTED_IDS = loadUnsupported();
    private static final SpdxCatalog SPDX = SpdxCatalog.defaultCatalog();

    private CycloneDx15SpdxCatalog() {
    }

    static boolean supports(String id) {
        return SPDX.canonicalLicense(id).filter(id::equals).isPresent() && !UNSUPPORTED_IDS.contains(id);
    }

    private static Set<String> loadUnsupported() {
        InputStream stream = CycloneDx15SpdxCatalog.class.getResourceAsStream(UNSUPPORTED);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled CycloneDX SPDX boundary " + UNSUPPORTED + ".");
        }
        Set<String> values = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#") && !values.add(value)) {
                    throw new IllegalStateException("Duplicate CycloneDX SPDX boundary identifier " + value + ".");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled CycloneDX SPDX boundary " + UNSUPPORTED + ".", exception);
        }
        return Set.copyOf(values);
    }
}
