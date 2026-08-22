package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class AuthoredJavaToolchainTest {
    @Test
    void preservesOnlyAuthoredMainRequestFieldsAndCopiesFeatures() {
        HashSet<JavaFeature> source = new HashSet<>(Set.of(JavaFeature.NATIVE_IMAGE));
        AuthoredJavaToolchain request = new AuthoredJavaToolchain(
                Optional.empty(),
                Optional.of(JavaDistribution.GRAALVM_COMMUNITY),
                Optional.of(source),
                Optional.of(ToolchainPolicy.REQUIRE_MANAGED));
        source.clear();

        assertEquals(Optional.empty(), request.version());
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), request.features().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> request.features().orElseThrow().clear());
    }

    @Test
    void distinguishesOmittedFeaturesFromAnExplicitEmptyList() {
        AuthoredJavaToolchain omitted = new AuthoredJavaToolchain(
                Optional.empty(),
                Optional.of(JavaDistribution.TEMURIN),
                Optional.empty(),
                Optional.empty());
        AuthoredJavaToolchain explicitEmpty = new AuthoredJavaToolchain(
                Optional.empty(),
                Optional.of(JavaDistribution.TEMURIN),
                Optional.of(Set.of()),
                Optional.empty());

        assertEquals(Optional.empty(), omitted.features());
        assertEquals(Optional.of(Set.of()), explicitEmpty.features());
    }

    @Test
    void acceptsEachMeaningfulMainFieldAndRejectsAnEmptyOrEmptyFeaturesOnlyTable() {
        new AuthoredJavaToolchain(
                Optional.of(new JavaFeatureRelease(21)), Optional.empty(), Optional.empty(), Optional.empty());
        new AuthoredJavaToolchain(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(ToolchainPolicy.ALLOW_SYSTEM));

        assertThrows(IllegalArgumentException.class, () -> new AuthoredJavaToolchain(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredJavaToolchain(
                Optional.empty(), Optional.empty(), Optional.of(Set.of()), Optional.empty()));
    }
}
