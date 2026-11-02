package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class KotlinCompilerCompatibilityPolicyTest {
    @Test
    void rejectsMainOptionsAgainstAnOlderCompiler() {
        ProjectConfig config = config("1.9.0", """
                [compiler]
                args = ["-Xannotation-default-target=param-property"]
                """);

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinCompilerCompatibilityPolicy.requireConfiguredOptionsSupported(
                        config, KotlinCompilationScope.MAIN));

        assertTrue(failure.getMessage().contains("Kotlin main compilation"), failure.getMessage());
        assertTrue(failure.getMessage().contains("[compiler].args"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-Xannotation-default-target=param-property"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("stable Kotlin 2.2.x"), failure.getMessage());
    }

    @Test
    void rejectsTestOptionsAgainstAnOlderCompiler() {
        ProjectConfig config = config("1.9.0", """
                [compiler.test]
                args = ["-Xannotation-default-target=param-property"]
                """);

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinCompilerCompatibilityPolicy.requireConfiguredOptionsSupported(
                        config, KotlinCompilationScope.TEST));

        assertTrue(failure.getMessage().contains("Kotlin test compilation"), failure.getMessage());
        assertTrue(failure.getMessage().contains("[compiler.test].args"), failure.getMessage());
    }

    @Test
    void acceptsStablePatchReleasesInTheQualifiedSeries() {
        ProjectConfig config = config("2.2.21", """
                [compiler]
                args = ["-Xannotation-default-target=param-property"]
                """);

        assertDoesNotThrow(() -> KotlinCompilerCompatibilityPolicy.requireConfiguredOptionsSupported(
                config, KotlinCompilationScope.MAIN));
        assertDoesNotThrow(() -> KotlinCompilerCompatibilityPolicy.requireSupportedToolchain(
                "2.2.21", KotlinCompilationScope.MAIN));
    }

    @Test
    void rejectsUnqualifiedOrPrereleaseToolchains() {
        for (String version : new String[] {"2.1.21", "2.3.0", "2.2.0-RC1", ""}) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> KotlinCompilerCompatibilityPolicy.requireSupportedToolchain(
                            version, KotlinCompilationScope.MAIN));
            assertTrue(failure.getMessage().contains("stable Kotlin 2.2.x"), failure.getMessage());
        }
    }

    @Test
    void leavesUnusedUnconfiguredArgumentListsAlone() {
        ProjectConfig config = config("1.9.0", "");

        assertDoesNotThrow(() -> KotlinCompilerCompatibilityPolicy.requireConfiguredOptionsSupported(
                config, KotlinCompilationScope.MAIN));
        assertDoesNotThrow(() -> KotlinCompilerCompatibilityPolicy.requireConfiguredOptionsSupported(
                config, KotlinCompilationScope.TEST));
    }

    private static ProjectConfig config(String version, String compilerSection) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "compatibility"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.kotlin]
                version = "%s"

                %s
                """.formatted(version, compilerSection));
    }
}
