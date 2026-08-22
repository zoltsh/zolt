package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CoverageSettingsTest {
    @Test
    void noneHasNoFloors() {
        assertFalse(CoverageSettings.none().hasAnyFloor());
    }

    @Test
    void detectsConfiguredFloor() {
        CoverageSettings settings = new CoverageSettings(
                Optional.of(88.0), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(settings.hasAnyFloor());
        assertEquals(Optional.of(88.0), settings.minLine());
    }

    @Test
    void acceptsBoundaryPercentages() {
        CoverageSettings settings = new CoverageSettings(
                Optional.of(0.0), Optional.of(100.0), Optional.empty(), Optional.empty());
        assertEquals(Optional.of(0.0), settings.minLine());
        assertEquals(Optional.of(100.0), settings.minBranch());
    }

    @Test
    void rejectsOutOfRangePercentage() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CoverageSettings(
                        Optional.of(120.0), Optional.empty(), Optional.empty(), Optional.empty()));
        assertTrue(exception.getMessage().contains("[coverage].line"));
    }

    @Test
    void normalizesNullOptionalsToEmpty() {
        CoverageSettings settings = new CoverageSettings(null, null, null, null);
        assertFalse(settings.hasAnyFloor());
        assertEquals(CoverageSettings.none(), settings);
    }
}
