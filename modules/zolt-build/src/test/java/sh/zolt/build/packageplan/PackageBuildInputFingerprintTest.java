package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageBuildInputFingerprintTest {
    @TempDir
    private Path projectRoot;

    @Test
    void producerFingerprintsAreAuthoritativeAndOrderIndependent() {
        GeneratedSourceProducerFingerprint alpha =
                producer("alpha", "producer-a");
        GeneratedSourceProducerFingerprint beta =
                producer("beta", "producer-b");

        String initial = fingerprint(List.of(alpha, beta));
        String reversed = fingerprint(List.of(beta, alpha));
        String changed = fingerprint(List.of(
                alpha,
                producer("beta", "producer-b-changed")));

        assertEquals(initial, reversed);
        assertNotEquals(initial, changed);
    }

    @Test
    void generatedStepDeclarationOrderDoesNotChangePackageFingerprint() {
        List<GeneratedSourceProducerFingerprint> producers =
                List.of(
                        producer("alpha", "producer-a"),
                        producer("beta", "producer-b"));

        assertEquals(
                fingerprint(config("alpha", "beta"), producers),
                fingerprint(config("beta", "alpha"), producers));
    }

    private String fingerprint(
            List<GeneratedSourceProducerFingerprint> producers) {
        return fingerprint(config(), producers);
    }

    private String fingerprint(
            ProjectConfig projectConfig,
            List<GeneratedSourceProducerFingerprint> producers) {
        return PackageBuildInputFingerprint.fingerprint(
                projectRoot,
                projectConfig,
                new ZoltLockfile(1, List.of(), List.of()),
                List.of(),
                producers);
    }

    private static ProjectConfig config(
            String first,
            String second) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.main.%s]
                kind = "declared-root"
                language = "java"
                inputs = ["schemas/%s.json"]
                output = "target/generated/%s"

                [generated.main.%s]
                kind = "declared-root"
                language = "java"
                inputs = ["schemas/%s.json"]
                output = "target/generated/%s"
                """.formatted(
                        first,
                        first,
                        first,
                        second,
                        second,
                        second));
    }

    private static GeneratedSourceProducerFingerprint producer(
            String stepId,
            String fingerprint) {
        return new GeneratedSourceProducerFingerprint(
                "main",
                stepId,
                GeneratedSourceKind.EXEC,
                fingerprint);
    }

    private static ProjectConfig config() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
    }
}
