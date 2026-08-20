package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.ZoltConfigException;

final class ManifestTestsDecoderTest {
    private final ManifestTestsDecoder decoder = new ManifestTestsDecoder();

    @Test
    void preservesWholeDomainOmissionAndExplicitEmptySuiteCollectionPresence() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[test.suites]\n",
                "test = { suites = {} }\n")) {
            assertEquals(AuthoredTests.empty(), decode(source).orElseThrow());
        }

        AuthoredTests runtimeOnly = decode("test.runtime.events = [\"failed\"]\n")
                .orElseThrow();
        assertTrue(runtimeOnly.sources().isEmpty());
        assertTrue(runtimeOnly.integration().isEmpty());
        assertTrue(runtimeOnly.suites().isEmpty());
        assertEquals(
                List.of(AuthoredTestRuntime.Event.FAILED),
                runtimeOnly.runtime().orElseThrow().events());
    }

    @Test
    void composesAllChildrenWithoutMaterializingDefaults() {
        AuthoredTests tests = decode("""
                [test.sources]
                java = ["src/custom-test/java"]

                [test.runtime]
                events = ["failed"]

                [test.integration]
                resources = ["src/custom-integration/resources"]

                [test.suites.zeta]
                tags = ["slow"]

                [test.suites.alpha]
                workers = 1
                """).orElseThrow();

        AuthoredTests.Sources sources = tests.sources().orElseThrow();
        assertEquals(List.of(path("src/custom-test/java")), sources.java());
        assertTrue(sources.groovy().isEmpty());
        AuthoredTestRuntime runtime = tests.runtime().orElseThrow();
        assertTrue(runtime.jvmArgs().isEmpty());
        assertTrue(runtime.properties().isEmpty());
        assertTrue(runtime.env().isEmpty());
        assertEquals(List.of(AuthoredTestRuntime.Event.FAILED), runtime.events());
        AuthoredTests.Integration integration = tests.integration().orElseThrow();
        assertTrue(integration.sources().isEmpty());
        assertEquals(
                List.of(path("src/custom-integration/resources")),
                integration.resources());
        assertEquals(
                List.of("alpha", "zeta"),
                tests.suites().keySet().stream().map(LocalId::value).toList());
        assertEquals(Optional.of(1), tests.suites().get(id("alpha")).workers());
        assertEquals(List.of("slow"), tests.suites().get(id("zeta")).tags());
        assertThrows(UnsupportedOperationException.class, sources.java()::clear);
        assertThrows(UnsupportedOperationException.class, runtime.events()::clear);
        assertThrows(UnsupportedOperationException.class, integration.resources()::clear);
        assertThrows(UnsupportedOperationException.class, tests.suites()::clear);
    }

    @Test
    void propagatesChildFailuresInSourcesRuntimeIntegrationThenSuitesOrder() {
        assertFailure("""
                [test.suites.invalid]
                classes = ["com/example/BadTest"]
                [test.integration]
                sources = ["custom/integration", "custom/integration"]
                [test.runtime]
                jvmArgs = ["${project.root}"]
                [test.sources]
                java = ["custom/java", "custom/java"]
                """, "`test.sources.java[1]`");
        assertFailure("""
                [test.suites.invalid]
                classes = ["com/example/BadTest"]
                [test.integration]
                sources = ["custom/integration", "custom/integration"]
                [test.runtime]
                jvmArgs = ["${project.root}"]
                """, "`test.runtime.jvmArgs[0]`");
        assertFailure("""
                [test.suites.invalid]
                classes = ["com/example/BadTest"]
                [test.integration]
                sources = ["custom/integration", "custom/integration"]
                """, "`test.integration.sources[1]`");
        assertFailure("""
                [test.suites.invalid]
                classes = ["com/example/BadTest"]
                """, "`test.suites.invalid.classes[0]`");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredTests> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
