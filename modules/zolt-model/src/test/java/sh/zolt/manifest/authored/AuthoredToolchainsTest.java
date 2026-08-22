package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeatureRelease;
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
}
