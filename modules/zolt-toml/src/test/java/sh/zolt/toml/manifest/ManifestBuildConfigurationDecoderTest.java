package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBuildConfigurationDecoderTest {
    private final ManifestBuildConfigurationDecoder decoder =
            new ManifestBuildConfigurationDecoder();

    @Test
    void preservesCompleteOmissionWithoutDefaults() {
        assertEquals(AuthoredBuildConfiguration.empty(), decode(""));
    }

    @Test
    void composesAllFiveChildrenWithoutMaterializingSiblingValues() {
        AuthoredBuildConfiguration configuration = decode("""
                [build.metadata]
                git = false

                [compiler.test]
                jdkApi = "host"

                [resources]
                main = ["custom/resources"]

                [test.runtime]
                events = ["failed"]

                [coverage]
                branch = 74.5
                """);

        assertTrue(configuration.build().orElseThrow().sources().isEmpty());
        assertFalse(configuration.build()
                .orElseThrow()
                .metadata()
                .orElseThrow()
                .git()
                .orElseThrow());
        AuthoredCompiler compiler = configuration.compiler().orElseThrow();
        assertTrue(compiler.encoding().isEmpty());
        assertTrue(compiler.args().isEmpty());
        assertEquals(
                AuthoredCompiler.JdkApiMode.HOST,
                compiler.test().orElseThrow().jdkApi().orElseThrow());
        AuthoredResources resources = configuration.resources().orElseThrow();
        assertEquals(List.of(path("custom/resources")), resources.main());
        assertTrue(resources.test().isEmpty());
        AuthoredTests tests = configuration.tests().orElseThrow();
        assertEquals(
                List.of(AuthoredTestRuntime.Event.FAILED),
                tests.runtime().orElseThrow().events());
        assertTrue(tests.suites().isEmpty());
        assertTrue(configuration.coverage().orElseThrow().line().isEmpty());
        assertEquals(
                74.5,
                configuration.coverage().orElseThrow().branch().orElseThrow().value());
        assertThrows(UnsupportedOperationException.class, resources.main()::clear);
    }

    @Test
    void preservesExplicitEmptyCollectionDomainsAcrossTableForms() {
        for (String source : List.of(
                """
                [resources.tokens]
                [test.suites]
                """,
                "resources = { tokens = {} }\ntest = { suites = {} }\n")) {
            AuthoredBuildConfiguration configuration = decode(source);
            assertEquals(AuthoredResources.empty(), configuration.resources().orElseThrow());
            assertEquals(AuthoredTests.empty(), configuration.tests().orElseThrow());
            assertTrue(configuration.build().isEmpty());
            assertTrue(configuration.compiler().isEmpty());
            assertTrue(configuration.coverage().isEmpty());
        }
    }

    @Test
    void propagatesChildFailuresInBuildCompilerResourcesTestsThenCoverageOrder() {
        assertFailure("""
                [coverage]
                line = 101
                [test.runtime]
                jvmArgs = ["${project.root}"]
                [resources]
                main = ["custom/resources", "custom/resources"]
                [compiler]
                args = ["--release=21"]
                [build]
                sources = ["custom/java", "custom/java"]
                """, "`build.sources`");
        assertFailure("""
                [coverage]
                line = 101
                [test.runtime]
                jvmArgs = ["${project.root}"]
                [resources]
                main = ["custom/resources", "custom/resources"]
                [compiler]
                args = ["--release=21"]
                """, "`compiler.args[0]`");
        assertFailure("""
                [coverage]
                line = 101
                [test.runtime]
                jvmArgs = ["${project.root}"]
                [resources]
                main = ["custom/resources", "custom/resources"]
                """, "`resources.main[1]`");
        assertFailure("""
                [coverage]
                line = 101
                [test.runtime]
                jvmArgs = ["${project.root}"]
                """, "`test.runtime.jvmArgs[0]`");
        assertFailure("""
                [coverage]
                line = 101
                """, "`coverage.line`");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private AuthoredBuildConfiguration decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private void assertFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
