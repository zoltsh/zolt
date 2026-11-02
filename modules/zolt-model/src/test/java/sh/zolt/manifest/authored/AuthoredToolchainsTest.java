package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.project.toolchain.GroovyToolchainVersion;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.KotlinToolchainVersion;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class AuthoredToolchainsTest {
    @Test
    void testRuntimeRequestMayExistWithoutAuthoredMainFields() {
        AuthoredJavaTestToolchain test = new AuthoredJavaTestToolchain(
                Optional.of(new JavaFeatureRelease(17)),
                Optional.of(JavaDistribution.TEMURIN),
                Optional.empty());
        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.of(new ZoltVersionPin("0.1.0")), Optional.empty(), Optional.of(test));

        assertTrue(toolchains.mainJava().isEmpty());
        assertEquals(17, toolchains.testJava().orElseThrow().version().orElseThrow().value());
    }

    @Test
    void testRuntimeRequestNeedsAtLeastOneFieldAndDoesNotExposeFeatures() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredJavaTestToolchain(
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(
                ToolchainPolicy.REQUIRE_MANAGED,
                new AuthoredJavaTestToolchain(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(ToolchainPolicy.REQUIRE_MANAGED))
                        .policy()
                        .orElseThrow());
    }

    @Test
    void emptyAggregateRepresentsNoAuthoredToolchainTables() {
        assertEquals(
                new AuthoredToolchains(Optional.empty(), Optional.empty(), Optional.empty()),
                AuthoredToolchains.empty());
    }

    @Test
    void carriesAnExactGroovyCompilerVersionWithoutMaterializingDefaults() {
        AuthoredGroovyToolchain groovy =
                new AuthoredGroovyToolchain(new GroovyToolchainVersion("4.0.22"));

        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(groovy));

        assertEquals(groovy, toolchains.groovy().orElseThrow());
        assertEquals("4.0.22", groovy.version().toString());
    }

    @Test
    void groovyCompilerVersionUsesTheFixedToolDependencyPolicy() {
        for (String invalid : List.of(
                "", " 4.0.22", "latest", "4.+", "[4.0,5.0)", "4.0-SNAPSHOT", "${groovy}")) {
            assertThrows(IllegalArgumentException.class, () -> new GroovyToolchainVersion(invalid));
        }

        assertEquals("4.0.22", new GroovyToolchainVersion("4.0.22").value());
    }

    @Test
    void carriesAnExactKotlinCompilerVersionWithoutMaterializingDefaults() {
        AuthoredKotlinToolchain kotlin =
                new AuthoredKotlinToolchain(new KotlinToolchainVersion("2.2.0"));

        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(kotlin));

        assertEquals(kotlin, toolchains.kotlin().orElseThrow());
        assertEquals("2.2.0", kotlin.version().toString());
    }

    @Test
    void kotlinCompilerVersionUsesTheFixedToolDependencyPolicy() {
        for (String invalid : List.of(
                "", " 2.2.0", "latest", "2.+", "[2.0,3.0)", "2.2-SNAPSHOT", "${kotlin}")) {
            assertThrows(IllegalArgumentException.class, () -> new KotlinToolchainVersion(invalid));
        }

        assertEquals("2.2.0", new KotlinToolchainVersion("2.2.0").value());
    }
}
