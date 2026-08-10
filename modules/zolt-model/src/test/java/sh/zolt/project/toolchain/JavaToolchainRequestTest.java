package sh.zolt.project.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class JavaToolchainRequestTest {
    @Test
    void normalizesNullOptionalFields() {
        JavaToolchainRequest request = new JavaToolchainRequest("21", Optional.empty(), null, null);

        assertEquals("21", request.version());
        assertEquals(Optional.empty(), request.distribution());
        assertTrue(request.features().isEmpty());
        assertEquals(ToolchainPolicy.PREFER_MANAGED, request.policy());
        assertEquals("any", request.distributionLabel());
        assertEquals("none", request.featuresLabel());
    }

    @Test
    void reportsNativeImageRequirement() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.REQUIRE_MANAGED);

        assertTrue(request.requiresNativeImage());
        assertEquals("graalvm-community", request.distributionLabel());
        assertEquals("native-image", request.featuresLabel());
    }

    @Test
    void rejectsBlankVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToolchainRequest(" ", Optional.empty(), Set.of(), ToolchainPolicy.ALLOW_SYSTEM));

        assertEquals("Java toolchain version is required.", exception.getMessage());
    }

    @Test
    void recognizesOnlyConcreteAsciiFeatureVersions() {
        assertTrue(JavaFeatureVersion.isConcrete("21"));
        assertTrue(JavaFeatureVersion.isConcrete("25"));
        assertFalse(JavaFeatureVersion.isConcrete("latest"));
        assertFalse(JavaFeatureVersion.isConcrete("021"));
        assertFalse(JavaFeatureVersion.isConcrete("٢٥"));
    }
}
