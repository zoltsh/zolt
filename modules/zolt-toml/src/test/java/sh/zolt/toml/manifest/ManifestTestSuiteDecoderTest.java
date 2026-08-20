package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.TestClassPattern;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestPaths;

final class ManifestTestSuiteDecoderTest {
    private final ManifestTestSuiteDecoder decoder = new ManifestTestSuiteDecoder();

    @Test
    void decodesEveryFieldAndKeepsAuthoredSelectionOrderWithCanonicalImmutableLocks() {
        ManifestTestSuiteDecoder.Decoded decoded = decode("""
                classes = ["*ZetaTest", "*AlphaTest"]
                excludeClasses = ["*ZetaFlakyTest", "*AlphaFlakyTest"]
                tags = ["zeta", "alpha"]
                excludeTags = ["slow", "flaky"]
                workers = 4
                locks = [
                    { class = "com.example.ZetaTest", resources = ["redis", "database"] },
                    { class = "com.example.AlphaTest", resources = ["network"] },
                ]
                """);
        AuthoredTestSuite suite = decoded.suite();

        assertEquals(new LocalId("unit"), decoded.id());
        assertEquals(List.of("*ZetaTest", "*AlphaTest"), patterns(suite.classes()));
        assertEquals(
                List.of("*ZetaFlakyTest", "*AlphaFlakyTest"),
                patterns(suite.excludeClasses()));
        assertEquals(List.of("zeta", "alpha"), suite.tags());
        assertEquals(List.of("slow", "flaky"), suite.excludeTags());
        assertEquals(Optional.of(4), suite.workers());
        assertEquals(
                List.of("com.example.AlphaTest", "com.example.ZetaTest"),
                suite.locks().stream().map(lock -> lock.className().value()).toList());
        assertEquals(
                List.of(new LocalId("database"), new LocalId("redis")),
                suite.locks().get(1).resources());
        assertThrows(UnsupportedOperationException.class, suite.classes()::clear);
        assertThrows(UnsupportedOperationException.class, suite.excludeClasses()::clear);
        assertThrows(UnsupportedOperationException.class, suite.tags()::clear);
        assertThrows(UnsupportedOperationException.class, suite.excludeTags()::clear);
        assertThrows(UnsupportedOperationException.class, suite.locks()::clear);
        assertThrows(
                UnsupportedOperationException.class,
                suite.locks().get(1).resources()::clear);
    }

    @Test
    void preservesWorkerOmissionAndExplicitOne() {
        assertTrue(suite("classes = [\"*Test\"]\n").workers().isEmpty());
        assertEquals(Optional.of(1), suite("workers = 1\n").workers());
    }

    @ParameterizedTest
    @MethodSource("selectionDuplicates")
    void anchorsSelectionDuplicatesAtTheLaterItem(
            String assignment,
            String path) {
        assertSemanticFailure(assignment, path, "duplicate");
    }

    static Stream<Arguments> selectionDuplicates() {
        return Stream.of(
                Arguments.of(
                        "classes = [\"*Test\", \"*Test\"]\n",
                        "`test.suites.unit.classes[1]`"),
                Arguments.of(
                        "excludeClasses = [\"*Test\", \"*Test\"]\n",
                        "`test.suites.unit.excludeClasses[1]`"),
                Arguments.of(
                        "tags = [\"fast\", \"fast\"]\n",
                        "`test.suites.unit.tags[1]`"),
                Arguments.of(
                        "excludeTags = [\"slow\", \"slow\"]\n",
                        "`test.suites.unit.excludeTags[1]`"));
    }

    @ParameterizedTest
    @MethodSource("invalidPatterns")
    void anchorsInvalidClassPatternsAtTheExactItem(
            String assignment,
            String path) {
        assertSemanticFailure(assignment, path, "not filesystem paths");
    }

    static Stream<Arguments> invalidPatterns() {
        return Stream.of(
                Arguments.of(
                        "classes = [\"*ValidTest\", \"com/example/BadTest\"]\n",
                        "`test.suites.unit.classes[1]`"),
                Arguments.of(
                        "excludeClasses = [\"*ValidTest\", \"com\\\\example\\\\BadTest\"]\n",
                        "`test.suites.unit.excludeClasses[1]`"));
    }

    @ParameterizedTest
    @MethodSource("invalidTags")
    void anchorsBlankAndControlTagsAtTheExactItem(
            String assignment,
            String path,
            String detail) {
        assertSemanticFailure(assignment, path, detail);
    }

    static Stream<Arguments> invalidTags() {
        return Stream.of(
                Arguments.of(
                        "tags = [\"valid\", \" \"]\n",
                        "`test.suites.unit.tags[1]`",
                        "must not be blank"),
                Arguments.of(
                        "excludeTags = [\"valid\", \"\\u0001\"]\n",
                        "`test.suites.unit.excludeTags[1]`",
                        "must not contain NUL or control characters"));
    }

    @ParameterizedTest
    @MethodSource("nonPositiveWorkers")
    void letsTheModelRejectNonPositiveWorkers(String value) {
        assertSemanticFailure(
                "workers = " + value + "\n",
                "`test.suites.unit.workers`",
                "Test suite workers must be a positive integer.");
    }

    static Stream<String> nonPositiveWorkers() {
        return Stream.of("0", "-1");
    }

    @ParameterizedTest
    @MethodSource("outOfRangeWorkers")
    void rejectsWorkersOutsideTheSignedIntRangeWithoutLeakingArithmeticException(
            String value) {
        ZoltConfigException failure = assertSemanticFailure(
                "workers = " + value + "\n",
                "`test.suites.unit.workers`",
                "Test suite workers must fit a signed 32-bit integer.");
        assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
    }

    static Stream<String> outOfRangeWorkers() {
        return Stream.of(Long.toString(Long.MAX_VALUE), Long.toString(Long.MIN_VALUE));
    }

    @ParameterizedTest
    @MethodSource("invalidLocks")
    void anchorsLockFailuresAtTheirCausalClassOrResource(
            String assignment,
            String path,
            String detail) {
        assertSemanticFailure(assignment, path, detail);
    }

    static Stream<Arguments> invalidLocks() {
        String first =
                "{ class = \"com.example.FirstTest\", resources = [\"database\"] }";
        return Stream.of(
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"*BadTest\", resources = [\"redis\"] }]\n",
                        "`test.suites.unit.locks[1].class`",
                        "suite locks require an exact class"),
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"com.example.EmptyTest\", resources = [] }]\n",
                        "`test.suites.unit.locks[1].resources`",
                        "must contain at least one local ID"),
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"com.example.InvalidTest\", "
                                + "resources = [\"redis\", \"Bad_Id\"] }]\n",
                        "`test.suites.unit.locks[1].resources[1]`",
                        "Invalid local ID"),
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"com.example.DuplicateResourceTest\", "
                                + "resources = [\"redis\", \"redis\"] }]\n",
                        "`test.suites.unit.locks[1].resources[1]`",
                        "duplicate"),
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"com.example.FirstTest\", resources = [\"redis\"] }]\n",
                        "`test.suites.unit.locks[1].class`",
                        "must appear only once"));
    }

    @ParameterizedTest
    @MethodSource("invalidLockShapes")
    void leavesMissingUnknownAndWrongKindLockMembersToShapeValidation(
            String assignment,
            String detail) {
        ZoltConfigException failure = assertFailure(assignment, detail);
        assertFalse(failure.getMessage().contains("locks.["), failure.getMessage());
    }

    static Stream<Arguments> invalidLockShapes() {
        String first =
                "{ class = \"com.example.FirstTest\", resources = [\"database\"] }";
        return Stream.of(
                Arguments.of(
                        "locks = [" + first + ", { resources = [\"redis\"] }]\n",
                        "`test.suites.unit.locks[1].class`"),
                Arguments.of(
                        "locks = [" + first + ", { class = \"com.example.MissingTest\" }]\n",
                        "`test.suites.unit.locks[1].resources`"),
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"com.example.UnknownTest\", resorces = [] }]\n",
                        "`test.suites.unit.locks[1].resorces`"),
                Arguments.of(
                        "locks = [{ class = 42, resources = [\"database\"] }]\n",
                        "`test.suites.unit.locks[0].class`"),
                Arguments.of(
                        "locks = [" + first
                                + ", { class = \"com.example.WrongTest\", resources = \"redis\" }]\n",
                        "`test.suites.unit.locks[1].resources`"));
    }

    @Test
    void acceptsEmptyEarlierFieldsWhenALaterFieldIsMeaningful() {
        AuthoredTestSuite suite = suite("""
                classes = []
                excludeClasses = []
                tags = ["fast"]
                """);

        assertTrue(suite.classes().isEmpty());
        assertTrue(suite.excludeClasses().isEmpty());
        assertEquals(List.of("fast"), suite.tags());
    }

    @Test
    void anchorsAllEmptyValuesToTheFirstCanonicalPresentField() {
        assertSemanticFailure("""
                locks = []
                excludeTags = []
                tags = []
                excludeClasses = []
                classes = []
                """, "`test.suites.unit.classes`", "Authored test suite must not be empty.");
    }

    @Test
    void followsCanonicalDiagnosticOrderDespiteReverseTomlAssignmentOrder() {
        ZoltConfigException failure = assertSemanticFailure("""
                locks = [{ class = "*BadTest", resources = [] }]
                workers = 0
                excludeTags = [" "]
                tags = [" "]
                excludeClasses = ["com/example/BadTest"]
                classes = ["com/example/BadTest"]
                """, "`test.suites.unit.classes[0]`", "not filesystem paths");
        assertFalse(failure.getMessage().contains(".locks"), failure.getMessage());
    }

    @Test
    void leavesAFieldlessNamedSuiteToStructuralValidation() {
        assertFailure("", "Manifest table `[test.suites.unit]` must not be empty");
    }

    @Test
    void enforcesNullContractsAndRequiresTheRetainedSectionEntry() {
        ManifestDecodeIndex index = index("classes = [\"*Test\"]\n");
        ManifestDecodeIndex.SectionEntry entry = entry(index);
        AuthoredTestSuite suite = decoder.decode(index, entry).suite();

        assertThrows(NullPointerException.class, () -> decoder.decode(null, entry));
        assertThrows(NullPointerException.class, () -> decoder.decode(index, null));
        assertThrows(
                NullPointerException.class,
                () -> new ManifestTestSuiteDecoder.Decoded(null, suite));
        assertThrows(
                NullPointerException.class,
                () -> new ManifestTestSuiteDecoder.Decoded(new LocalId("unit"), null));

        ManifestDecodeIndex other = index("tags = [\"fast\"]\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(index, entry(other)));
    }

    private ManifestTestSuiteDecoder.Decoded decode(String assignments) {
        ManifestDecodeIndex index = index(assignments);
        return decoder.decode(index, entry(index));
    }

    private AuthoredTestSuite suite(String assignments) {
        return decode(assignments).suite();
    }

    private static ManifestDecodeIndex index(String assignments) {
        return ManifestSemanticTestSupport.index(
                "[test.suites.unit]\n" + assignments);
    }

    private static ManifestDecodeIndex.SectionEntry entry(ManifestDecodeIndex index) {
        return index.sectionEntries(FinalManifestPaths.TEST_SUITE).getFirst();
    }

    private static ZoltConfigException assertSemanticFailure(
            String assignments,
            String... details) {
        ZoltConfigException failure = assertFailure(assignments, details);
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private static ZoltConfigException assertFailure(
            String assignments,
            String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> new ManifestTestSuiteDecoderTest().decode(assignments));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
        return failure;
    }

    private static List<String> patterns(List<TestClassPattern> values) {
        return values.stream().map(TestClassPattern::value).toList();
    }

}
