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
    void mixedGroovyAndKotlinRejectionWinsOverJavaAndKotlin() {
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(Path.of("Main.java")),
                List.of(Path.of("Main.groovy")),
                List.of(Path.of("Main.kt")),
                List.of(),
                List.of(),
                List.of());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> SourceLanguagePolicy.requireMainSupported(sources));

        assertEquals(
                "The main source set combines Groovy and Kotlin, which Zolt does not support.",
                exception.actionableError().summary());
        assertEquals(
                "Use either Groovy or Kotlin for the main source set, then run `zolt build` again.",
                exception.actionableError().remediation());
    }

    @Test
    void javaAndKotlinIsRejectedForTheBoundedPreview() {
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
                "The main source set combines Java and Kotlin, which the Kotlin preview does not support.",
                exception.actionableError().summary());
        assertEquals(
                "Use a Kotlin-only main source set or remove Kotlin, then run `zolt build` again.",
                exception.actionableError().remediation());
    }

    @Test
    void kotlinOnlyMainIsAllowed() {
        assertDoesNotThrow(() -> SourceLanguagePolicy.requireMainSupported(sources(
                List.of(), List.of(Path.of("Main.kt")), List.of(), List.of())));
    }

    @Test
    void mainAndTestScopesAreIndependent() {
        SourceDiscoveryResult sources = sources(
                List.of(),
                List.of(Path.of("Main.kt")),
                List.of(),
                List.of(Path.of("MainTest.kt")));

        assertDoesNotThrow(() -> SourceLanguagePolicy.requireMainSupported(sources));
        assertDoesNotThrow(() -> SourceLanguagePolicy.requireTestSupported(sources));
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
                "Use either Groovy or Kotlin for the test source set, then run `zolt test` again.",
                exception.actionableError().remediation());
    }

    @Test
    void javaAndKotlinTestIsRejectedForTheBoundedPreview() {
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(Path.of("MainTest.java")),
                List.of(),
                List.of(Path.of("MainTest.kt")));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> SourceLanguagePolicy.requireTestSupported(sources));

        assertEquals(
                "The test source set combines Java and Kotlin, which the Kotlin preview does not support.",
                exception.actionableError().summary());
        assertEquals(
                "Use a Kotlin-only test source set or remove Kotlin, then run `zolt test` again.",
                exception.actionableError().remediation());
    }

    @Test
    void kotlinOnlyTestIsAllowed() {
        assertDoesNotThrow(() -> SourceLanguagePolicy.requireTestSupported(sources(
                List.of(), List.of(), List.of(), List.of(Path.of("MainTest.kt")))));
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
