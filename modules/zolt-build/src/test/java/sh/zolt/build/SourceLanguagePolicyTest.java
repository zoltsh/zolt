package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.build.discovery.SourceDiscoveryResult;

final class SourceLanguagePolicyTest {
    @Test
    void mixedGroovyAndKotlinIsRejectedBeforeTemporaryKotlinUnavailability() {
        SourceDiscoveryResult sources = sources(
                List.of(Path.of("Main.groovy")),
                List.of(Path.of("Main.kt")),
                List.of(),
                List.of());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> SourceLanguagePolicy.requireMainSupported(sources));

        assertEquals(
                "The main source set combines Groovy and Kotlin, which Zolt does not support.",
                exception.actionableError().summary());
        assertEquals(
                "Remove Kotlin from the main source set; Kotlin-only compilation is not available yet. "
                        + "Then run `zolt build` again.",
                exception.actionableError().remediation());
    }

    @Test
    void javaAndKotlinUsesTheTemporaryUnavailableDiagnostic() {
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(Path.of("Main.java")),
                List.of(),
                List.of(Path.of("Main.kt")),
                List.of(),
                List.of(),
                List.of());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> SourceLanguagePolicy.requireMainSupported(sources));

        assertEquals(
                "Zolt recognized Kotlin main sources, but Kotlin compilation is not available yet.",
                exception.actionableError().summary());
        assertEquals(
                "Remove the Kotlin main sources or use another compiler, then run `zolt build` again.",
                exception.actionableError().remediation());
    }

    @Test
    void mainAndTestScopesAreIndependent() {
        SourceDiscoveryResult sources = sources(
                List.of(),
                List.of(Path.of("Main.kt")),
                List.of(),
                List.of(Path.of("MainTest.kt")));

        assertThrows(BuildException.class, () -> SourceLanguagePolicy.requireMainSupported(sources));
        assertThrows(BuildException.class, () -> SourceLanguagePolicy.requireTestSupported(sources));
        assertDoesNotThrow(() -> SourceLanguagePolicy.requireMainSupported(sources(
                List.of(), List.of(), List.of(), List.of(Path.of("MainTest.kt")))));
        assertDoesNotThrow(() -> SourceLanguagePolicy.requireTestSupported(sources(
                List.of(), List.of(Path.of("Main.kt")), List.of(), List.of())));
    }

    @Test
    void mixedTestSourcesUseTheTestSpecificDiagnostic() {
        BuildException exception = assertThrows(
                BuildException.class,
                () -> SourceLanguagePolicy.requireTestSupported(sources(
                        List.of(),
                        List.of(),
                        List.of(Path.of("MainSpec.groovy")),
                        List.of(Path.of("MainTest.kt")))));

        assertEquals(
                "The test source set combines Groovy and Kotlin, which Zolt does not support.",
                exception.actionableError().summary());
        assertEquals(
                "Remove Kotlin from the test source set; Kotlin-only compilation is not available yet. "
                        + "Then run `zolt test` again.",
                exception.actionableError().remediation());
    }

    private static SourceDiscoveryResult sources(
            List<Path> groovyMain,
            List<Path> kotlinMain,
            List<Path> groovyTest,
            List<Path> kotlinTest) {
        return new SourceDiscoveryResult(
                List.of(),
                groovyMain,
                kotlinMain,
                List.of(),
                groovyTest,
                kotlinTest);
    }
}
