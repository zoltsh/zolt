package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredCoverageTest {
    @Test
    void acceptsIntegerAndFractionalInclusiveFloors() {
        AuthoredCoverage coverage = new AuthoredCoverage(
                Optional.of(new CoveragePercentage(0)),
                Optional.of(new CoveragePercentage(74.5)),
                Optional.empty(),
                Optional.of(new CoveragePercentage(100)));

        assertEquals(0.0, coverage.line().orElseThrow().value());
        assertEquals(74.5, coverage.branch().orElseThrow().value());
        assertEquals(100.0, coverage.method().orElseThrow().value());
        assertEquals(
                Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(new CoveragePercentage(-0.0).value()));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeFloorsAndAnEmptyTable() {
        for (double invalid : new double[] {
            -0.01, 100.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            assertThrows(IllegalArgumentException.class, () -> new CoveragePercentage(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCoverage(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void aggregatePreservesCompleteSectionOmission() {
        AuthoredBuildConfiguration empty = AuthoredBuildConfiguration.empty();

        assertEquals(Optional.empty(), empty.build());
        assertEquals(Optional.empty(), empty.compiler());
        assertEquals(Optional.empty(), empty.resources());
        assertEquals(Optional.empty(), empty.tests());
        assertEquals(Optional.empty(), empty.coverage());
    }
}
