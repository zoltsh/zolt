package sh.zolt.project.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class JavaFeatureReleaseTest {
    @Test
    void acceptsPositiveIntegerReleases() {
        JavaFeatureRelease release = new JavaFeatureRelease(21);

        assertEquals(21, release.value());
        assertEquals("21", release.toString());
    }

    @Test
    void rejectsZeroAndNegativeReleases() {
        IllegalArgumentException zero = assertThrows(
                IllegalArgumentException.class, () -> new JavaFeatureRelease(0));
        IllegalArgumentException negative = assertThrows(
                IllegalArgumentException.class, () -> new JavaFeatureRelease(-1));

        assertEquals("Java feature release must be a positive integer.", zero.getMessage());
        assertEquals("Java feature release must be a positive integer.", negative.getMessage());
    }
}
