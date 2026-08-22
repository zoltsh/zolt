package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import sh.zolt.project.ProjectConfig;

/**
 * Design §7.3: Zolt derives Maven license metadata only for a license it actually knows. An SPDX
 * expression, an unknown identifier, or a custom license has no page on the SPDX license list, so a
 * derived URL there would be a fabricated 404 in every published POM and SBOM.
 */
final class ManifestLicenseMetadataAdapterTest {
    @Test
    void derivesTheSpdxUrlForACanonicalInlineIdentifier() {
        ProjectConfig adapted = license("""
                license = { id = "apache-2.0", name = "Apache License 2.0" }
                """);

        assertEquals("Apache License 2.0", adapted.packageSettings().metadata().license());
        assertEquals(
                "https://spdx.org/licenses/Apache-2.0.html",
                adapted.packageSettings().metadata().licenseUrl(),
                "the catalog's canonical spelling names the SPDX page, not the authored casing");
    }

    @Test
    void derivesNoUrlForAnSpdxExpression() {
        ProjectConfig adapted = license("""
                license = { id = "Apache-2.0 OR MIT", name = "Apache License 2.0 or MIT License" }
                """);

        assertEquals("Apache License 2.0 or MIT License", adapted.packageSettings().metadata().license());
        assertEquals("", adapted.packageSettings().metadata().licenseUrl());
    }

    @Test
    void derivesNoUrlForAnUnknownIdentifier() {
        ProjectConfig adapted = license("""
                license = { id = "Acme-Internal-1.0", name = "Acme Internal License" }
                """);

        assertEquals("", adapted.packageSettings().metadata().licenseUrl());
    }

    @Test
    void keepsTheAuthoredUrlForAnyLicenseThatStatesOne() {
        ProjectConfig adapted = license("""
                license = { id = "Apache-2.0 OR MIT", name = "Dual licensed", url = "https://example.test/licenses" }
                """);

        assertEquals("https://example.test/licenses", adapted.packageSettings().metadata().licenseUrl());
    }

    private static ProjectConfig license(String licenseLine) {
        return FinalManifests.load("""
                [project]
                name = "library"
                version = "1.0.0"
                group = "com.example"
                java = 21
                %s
                """.formatted(licenseLine.strip()));
    }
}
