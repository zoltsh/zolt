package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.toml.ZoltConfigException;

final class ManifestCoverageDecoderTest {
    private final ManifestCoverageDecoder decoder = new ManifestCoverageDecoder();

    @Test
    void preservesOmissionAndPartialDottedOrInlinePresence() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "coverage.branch = 74.5\n",
                "coverage = { branch = 74.5 }\n")) {
            AuthoredCoverage coverage = decode(source).orElseThrow();
            assertTrue(coverage.line().isEmpty());
            assertEquals(74.5, coverage.branch().orElseThrow().value());
            assertTrue(coverage.instruction().isEmpty());
            assertTrue(coverage.method().isEmpty());
        }
    }

    @Test
    void decodesIntegerFractionalAndInclusiveFloors() {
        AuthoredCoverage coverage = decode("""
                [coverage]
                method = 100
                instruction = 0
                branch = 74.5
                line = 88
                """).orElseThrow();

        assertEquals(88.0, coverage.line().orElseThrow().value());
        assertEquals(74.5, coverage.branch().orElseThrow().value());
        assertEquals(0.0, coverage.instruction().orElseThrow().value());
        assertEquals(100.0, coverage.method().orElseThrow().value());
    }

    @Test
    void normalizesNegativeZeroThroughTheCoverageModel() {
        double value = decode("coverage.line = -0.0\n")
                .orElseThrow()
                .line()
                .orElseThrow()
                .value();

        assertEquals(
                Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(value));
    }

    @ParameterizedTest
    @MethodSource("invalidFloors")
    void anchorsNonFiniteAndOutOfRangeFloorsToTheirExactFields(
            String source,
            String path) {
        ZoltConfigException failure = assertSemanticFailure(source, path);
        assertTrue(failure.getMessage().contains(
                "Coverage percentage must be finite and between 0 and 100 inclusive."));
    }

    private static List<Arguments> invalidFloors() {
        return List.of(
                Arguments.of("coverage.line = -0.01\n", "coverage.line"),
                Arguments.of("coverage.branch = 100.01\n", "coverage.branch"),
                Arguments.of("coverage.instruction = nan\n", "coverage.instruction"),
                Arguments.of("coverage.method = +inf\n", "coverage.method"),
                Arguments.of("coverage.method = -inf\n", "coverage.method"));
    }

    @Test
    void followsCanonicalDiagnosticOrderDespiteReverseAssignments() {
        assertSemanticFailure("""
                [coverage]
                method = 101
                instruction = 101
                branch = 101
                line = 101
                """, "coverage.line");
        assertSemanticFailure("""
                [coverage]
                method = 101
                instruction = 101
                branch = 101
                """, "coverage.branch");
        assertSemanticFailure("""
                [coverage]
                method = 101
                instruction = 101
                """, "coverage.instruction");
        assertSemanticFailure("coverage.method = 101\n", "coverage.method");
    }

    @Test
    void leavesEmptyTablesAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[coverage]\n",
                "Manifest table `[coverage]` must not be empty");
        assertShapeFailure(
                "coverage = {}\n",
                "Manifest table `[coverage]` must not be empty");
        assertShapeFailure(
                "coverage.line = \"high\"\n",
                "expected number but found string");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredCoverage> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private ZoltConfigException assertSemanticFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains("`" + path + "`"), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }
}
