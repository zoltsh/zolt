package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;

final class AuthoredBuildTest {
    @Test
    void retainsAuthoredOutputAndBooleanPresenceWithoutDefaults() {
        ArrayList<ManifestRelativePath> sources = new ArrayList<>(List.of(
                path("src/generated/java"), path("src/main/java")));
        AuthoredBuild.Output output = new AuthoredBuild.Output(
                Optional.of(path("target")),
                Optional.of(path("classes")),
                Optional.empty(),
                Optional.of(path("integration-test-classes")));
        AuthoredBuild.Metadata metadata = new AuthoredBuild.Metadata(
                Optional.of(false), Optional.empty(), Optional.of(true));

        AuthoredBuild build = new AuthoredBuild(
                sources, Optional.of(output), Optional.of(metadata));
        sources.clear();

        assertEquals(
                List.of(path("src/generated/java"), path("src/main/java")), build.sources());
        assertEquals(path("classes"), build.output().orElseThrow().main().orElseThrow());
        assertFalse(build.metadata().orElseThrow().buildInfo().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> build.sources().clear());
    }

    @Test
    void rejectsDuplicateSourceRootsAndEmptySingletonSections() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredBuild(
                List.of(path("src/main/java"), path("src/main/java")),
                Optional.empty(),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredBuild(
                List.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredBuild.Output(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredBuild.Metadata(
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
