package sh.zolt.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class VersionClassifierTest {
    private final VersionClassifier classifier = new VersionClassifier();

    @Test
    void splitsCandidatesByChangeClass() {
        VersionCandidates candidates =
                classifier.candidates("1.2.3", List.of("1.2.4", "1.3.0", "2.0.0"), false);
        assertEquals("1.2.4", candidates.latestPatch().orElseThrow());
        assertEquals("1.3.0", candidates.latestMinor().orElseThrow());
        assertEquals("2.0.0", candidates.latestMajor().orElseThrow());
        assertEquals("1.3.0", candidates.selectedInMajor().orElseThrow());
        assertEquals("2.0.0", candidates.selectedLatest().orElseThrow());
        assertTrue(candidates.updateAvailable());
    }

    @Test
    void ignoresEqualAndOlderVersions() {
        VersionCandidates candidates =
                classifier.candidates("1.2.3", List.of("1.2.3", "1.2.2", "1.1.9"), false);
        assertTrue(candidates.latestPatch().isEmpty());
        assertTrue(candidates.latestMinor().isEmpty());
        assertTrue(candidates.latestMajor().isEmpty());
        assertFalse(candidates.updateAvailable());
    }

    @Test
    void snapshotsAreNeverSuggested() {
        VersionCandidates candidates =
                classifier.candidates("1.2.3", List.of("1.2.4-SNAPSHOT", "1.3.0-SNAPSHOT"), true);
        assertFalse(candidates.updateAvailable());
    }

    @Test
    void prereleasesAreExcludedByDefaultAndWidenedOnRequest() {
        VersionCandidates stable =
                classifier.candidates("1.2.3", List.of("1.3.0-rc1"), false);
        assertFalse(stable.updateAvailable());

        VersionCandidates widened =
                classifier.candidates("1.2.3", List.of("1.3.0-rc1"), true);
        assertEquals("1.3.0-rc1", widened.latestMinor().orElseThrow());
        assertEquals("1.3.0-rc1", widened.latestMajor().orElseThrow());
        assertTrue(widened.latestPatch().isEmpty());
    }

    @Test
    void expandedPrereleaseTokensAreExcludedByDefaultAndWidenedOnRequest() {
        VersionCandidates stable = classifier.candidates(
                "1.2.3", List.of("2.0.0-ea.1", "1.5.0-preview", "1.3.0-nightly"), false);
        assertFalse(stable.updateAvailable());

        VersionCandidates widened =
                classifier.candidates("1.2.3", List.of("2.0.0-ea.1", "1.5.0-preview"), true);
        assertEquals("1.5.0-preview", widened.selectedInMajor().orElseThrow());
        assertEquals("2.0.0-ea.1", widened.selectedLatest().orElseThrow());
    }

    @Test
    void prereleaseWideningPrefersLatestPrerelease() {
        VersionCandidates widened =
                classifier.candidates("1.2.3", List.of("1.3.0-rc1", "1.3.0-rc2"), true);
        assertEquals("1.3.0-rc2", widened.latestMinor().orElseThrow());
    }

    @Test
    void prereleaseCurrentSeesSameCoreGaAsPatch() {
        VersionCandidates candidates =
                classifier.candidates("1.2.0-rc1", List.of("1.2.0"), false);
        assertEquals("1.2.0", candidates.latestPatch().orElseThrow());
        assertEquals(UpdateClass.PATCH, classifier.classify("1.2.0-rc1", "1.2.0"));
    }

    @Test
    void gaBeatsPrereleaseWithinSameCore() {
        VersionCandidates widened =
                classifier.candidates("1.2.0-rc1", List.of("1.2.0-rc2", "1.2.0"), true);
        assertEquals("1.2.0", widened.latestPatch().orElseThrow());
    }

    @Test
    void handlesJreFlavoredCoordinates() {
        VersionCandidates candidates = classifier.candidates(
                "33.4.0-jre", List.of("33.4.8-jre", "33.5.0-jre", "34.0.0-jre"), false);
        assertEquals("33.4.8-jre", candidates.latestPatch().orElseThrow());
        assertEquals("33.5.0-jre", candidates.latestMinor().orElseThrow());
        assertEquals("34.0.0-jre", candidates.latestMajor().orElseThrow());
        assertEquals(UpdateClass.PATCH, classifier.classify("33.4.0-jre", "33.4.8-jre"));
        assertEquals(UpdateClass.MINOR, classifier.classify("33.4.0-jre", "33.5.0-jre"));
        assertEquals(UpdateClass.MAJOR, classifier.classify("33.4.0-jre", "34.0.0-jre"));
    }

    @Test
    void handlesCalendarVersions() {
        VersionCandidates candidates = classifier.candidates(
                "2023.10.5", List.of("2023.10.6", "2023.11.0", "2024.1.0"), false);
        assertEquals("2023.10.6", candidates.latestPatch().orElseThrow());
        assertEquals("2023.11.0", candidates.latestMinor().orElseThrow());
        assertEquals("2024.1.0", candidates.latestMajor().orElseThrow());
    }

    @Test
    void classifyReadsReleaseCore() {
        assertEquals(UpdateClass.PATCH, classifier.classify("1.2.3", "1.2.9"));
        assertEquals(UpdateClass.MINOR, classifier.classify("1.2.3", "1.5.0"));
        assertEquals(UpdateClass.MAJOR, classifier.classify("1.2.3", "3.0.0"));
        assertEquals(UpdateClass.PATCH, classifier.classify("1.2", "1.2.1"));
        assertEquals(UpdateClass.MINOR, classifier.classify("1", "1.4"));
    }

    @Test
    void classifiesNumericComponentsWithoutOverflow() {
        assertEquals(UpdateClass.PATCH, classifier.classify(
                "1.999999999999999999999999999999.1",
                "1.999999999999999999999999999999.2"));
        assertEquals(UpdateClass.MINOR, classifier.classify(
                "1.999999999999999999999999999999.1",
                "1.1000000000000000000000000000000.0"));
        assertEquals(UpdateClass.MAJOR, classifier.classify(
                "999999999999999999999999999999.0.0",
                "1000000000000000000000000000000.0.0"));
    }

    @Test
    void patchCeilingStaysWithinCurrentMinor() {
        VersionCandidates candidates = classifier.candidates(
                "1.2.3", List.of("1.2.4", "1.2.9", "1.4.0"), false);
        assertEquals("1.2.9", candidates.latestPatch().orElseThrow());
        assertEquals("1.4.0", candidates.latestMinor().orElseThrow());
    }
}
