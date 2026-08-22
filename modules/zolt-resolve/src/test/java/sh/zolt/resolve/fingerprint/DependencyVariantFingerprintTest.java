package sh.zolt.resolve.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Schema v2 puts the dependency variant — classifier and type — into lock identity.
 *
 * <p>A variant selects which published artifact a coordinate resolves to, so editing one changes what
 * the build downloads and runs. While the fingerprint omitted it, a manifest could switch a native
 * library from one platform to another, or a dependency from a jar to a tarball, and the checked-in
 * lock stayed "fresh" over artifacts it never certified.
 */
final class DependencyVariantFingerprintTest {
    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();

    /**
     * Design §9.7 normalizes a variant to the coordinate plus classifier and type, with {@code jar}
     * and no classifier as the default. Both spellings of that default select the same artifact, so
     * neither the fingerprint nor the generated POM may distinguish them — otherwise adding a
     * redundant field would restate a lock and rewrite a published POM for no change in meaning.
     */
    @Test
    void explicitJarTypeMatchesDefaultVariant() {
        ProjectConfig implicit = parse(variantToml("\"1.0.0\""));
        ProjectConfig explicit = parse(variantToml("{ version = \"1.0.0\", type = \"jar\" }"));

        assertEquals(
                ProjectResolutionFingerprint.inputs(implicit),
                ProjectResolutionFingerprint.inputs(explicit));
        assertEquals(
                ProjectResolutionFingerprint.fingerprint(implicit),
                ProjectResolutionFingerprint.fingerprint(explicit));
        assertEquals(implicit.dependencyMetadata(), explicit.dependencyMetadata());
    }

    @Test
    void classifierAndTypeEnterTheFingerprintAsANondefaultVariant() {
        List<String> inputs = ProjectResolutionFingerprint.inputs(parse(
                variantToml("{ version = \"1.0.0\", classifier = \"linux-x86_64\", type = \"tar.gz\" }")));

        assertTrue(
                inputs.contains("dependencyVariant\tdependencies\tcom.example:client\tlinux-x86_64\ttar.gz"),
                () -> "expected the canonical variant line: " + inputs);
        assertNotEquals(
                ProjectResolutionFingerprint.fingerprint(parse(
                        variantToml("{ version = \"1.0.0\", classifier = \"linux-x86_64\" }"))),
                ProjectResolutionFingerprint.fingerprint(parse(
                        variantToml("{ version = \"1.0.0\", classifier = \"osx-aarch64\" }"))));
    }

    private ProjectConfig parse(String toml) {
        return manifestLoader.load(toml);
    }

    private static String variantToml(String declaration) {
        return """
                [project]
                name = "variant"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:client" = %s
                """.formatted(declaration);
    }
}
