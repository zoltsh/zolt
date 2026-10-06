package sh.zolt.resolve.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class KotlinToolchainResolutionFingerprintTest {
    @Test
    void fingerprintsOnlyConfiguredKotlinToolchainsWithTheirExactVersion() {
        ProjectConfig absent = config("");
        ProjectConfig version22 = config("2.2.0");
        ProjectConfig version23 = config("2.2.10");

        assertFalse(ProjectResolutionFingerprint.inputs(absent).stream()
                .anyMatch(input -> input.startsWith("toolchain.kotlin\t")));
        assertEquals(
                List.of("toolchain.kotlin\torg.jetbrains.kotlin:kotlin-compiler-embeddable\t2.2.0\tconflict-provenance-v1\texact-root-v1"),
                ProjectResolutionFingerprint.inputs(version22).stream()
                        .filter(input -> input.startsWith("toolchain.kotlin\t"))
                        .toList());
        assertNotEquals(
                ProjectResolutionFingerprint.fingerprint(version22),
                ProjectResolutionFingerprint.fingerprint(version23));
        assertNotEquals(
                category(version22, "toolchain.kotlin"),
                category(version23, "toolchain.kotlin"));
    }

    @Test
    void fingerprintsTheVersionAlignedSerializationPlugin() {
        ProjectConfig plain = config("2.2.0");
        ProjectConfig serialization = config("2.2.0", true);

        assertEquals(
                List.of(
                        "toolchain.kotlin\torg.jetbrains.kotlin:kotlin-compiler-embeddable\t2.2.0\tconflict-provenance-v1\texact-root-v1",
                        "toolchain.kotlin\torg.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable\t2.2.0\tconflict-provenance-v1\texact-root-v1"),
                ProjectResolutionFingerprint.inputs(serialization).stream()
                        .filter(input -> input.startsWith("toolchain.kotlin\t"))
                        .toList());
        assertNotEquals(
                ProjectResolutionFingerprint.fingerprint(plain),
                ProjectResolutionFingerprint.fingerprint(serialization));
    }

    private static String category(ProjectConfig config, String category) {
        return ProjectResolutionFingerprint.inputFingerprints(config).stream()
                .filter(value -> value.startsWith(category + "="))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectConfig config(String kotlinVersion) {
        return config(kotlinVersion, false);
    }

    private static ProjectConfig config(String kotlinVersion, boolean serialization) {
        String plugins = serialization ? "\nplugins = [\"serialization\"]" : "";
        String toolchain = kotlinVersion.isBlank()
                ? ""
                : """

                  [toolchain.kotlin]
                  version = "%s"%s
                  """.formatted(kotlinVersion, plugins);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "fingerprint-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                %s
                """.formatted(toolchain));
    }
}
