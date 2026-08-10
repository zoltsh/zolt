package sh.zolt.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class VersionComparatorTest {
    private final VersionComparator comparator = new VersionComparator();

    @Test
    void normalSemverVersionsSortCorrectly() {
        assertOlder("1.0.0", "1.0.1");
        assertOlder("1.0.9", "1.0.10");
        assertOlder("1.9.0", "1.10.0");
        assertOlder("2.0.0", "10.0.0");
    }

    @Test
    void numericComponentsDoNotOverflow() {
        assertOlder("1.999999999999999999999999999999.0", "1.1000000000000000000000000000000.0");
        assertEquals(0, comparator.compare("1.000000000000000000000000000001", "1.1"));
    }

    @Test
    void missingPatchIsEquivalentToZeroPatch() {
        assertEquals(0, comparator.compare("1.0", "1.0.0"));
        assertEquals(0, comparator.compare("1", "1.0.0"));
    }

    @Test
    void knownQualifiersSortPredictably() {
        assertOlder("1.0-alpha", "1.0-beta");
        assertOlder("1.0-beta", "1.0-rc1");
        assertOlder("1.0-rc1", "1.0");
        assertOlder("1.0", "1.0-sp1");
    }

    @Test
    void finalGaAndReleaseAreEquivalentToPlainRelease() {
        assertEquals(0, comparator.compare("1.0-final", "1.0"));
        assertEquals(0, comparator.compare("1.0-ga", "1.0"));
        assertEquals(0, comparator.compare("1.0-release", "1.0"));
    }

    @Test
    void unknownQualifiersFallBackToDeterministicComparison() {
        assertOlder("1.0-foo", "1.0-zed");
        assertOlder("1.0-foo1", "1.0-foo2");
    }

    @Test
    void expandedPrereleaseTokensSortBelowTheirRelease() {
        // Consistency with VersionStability: a version it classifies PRERELEASE also orders below its
        // GA, so update/outdated ranking can always move off a prerelease onto the matching release.
        assertOlder("2.0.0-ea.1", "2.0.0");
        assertOlder("1.5.0-preview", "1.5.0");
        assertOlder("3.0.0-dev", "3.0.0");
        assertOlder("0.9.0-nightly", "0.9.0");
        assertOlder("1.0.0-canary.3", "1.0.0");
        assertOlder("1.2.0-pre", "1.2.0");
        assertOlder("1.0.0-experimental", "1.0.0");
    }

    @Test
    void commonRealWorldVersionsSort() {
        assertOlder("5.11.3", "5.11.4");
        assertOlder("2.0.15", "2.0.16");
        assertOlder("33.3.1-jre", "33.4.0-jre");
        assertOlder("1.0.0-rc1", "1.0.0");
    }

    @Test
    void sortedListIsDeterministic() {
        List<String> versions = new ArrayList<>(List.of(
                "1.0",
                "1.0-beta",
                "1.0-alpha",
                "1.0-rc1",
                "1.0-sp1",
                "1.0-foo"));

        versions.sort(comparator);

        assertEquals(List.of(
                "1.0-foo",
                "1.0-alpha",
                "1.0-beta",
                "1.0-rc1",
                "1.0",
                "1.0-sp1"), versions);
    }

    private void assertOlder(String left, String right) {
        assertTrue(comparator.compare(left, right) < 0, left + " should be older than " + right);
        assertTrue(comparator.compare(right, left) > 0, right + " should be newer than " + left);
    }
}
