package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPlatformsDecoderTest {
    @Test
    void decodesEverySelectorFormIntoTheModelSortedMap() {
        AuthoredPlatforms platforms = decode("""
                [platforms]
                "org.example:zeta" = { versionRef = "not-declared-here" }
                "org.example:alpha" = "1.0-SNAPSHOT"
                "org.example:middle" = { version = "1.5.0" }
                """);

        assertEquals(
                List.of(
                        new DependencyCoordinate("org.example:alpha"),
                        new DependencyCoordinate("org.example:middle"),
                        new DependencyCoordinate("org.example:zeta")),
                List.copyOf(platforms.entries().keySet()));
        assertInstanceOf(
                PlatformSelector.FixedVersion.class,
                platforms.entries().get(new DependencyCoordinate("org.example:alpha")));
        assertInstanceOf(
                PlatformSelector.FixedVersion.class,
                platforms.entries().get(new DependencyCoordinate("org.example:middle")));
        PlatformSelector.VersionReference reference = assertInstanceOf(
                PlatformSelector.VersionReference.class,
                platforms.entries().get(new DependencyCoordinate("org.example:zeta")));
        assertEquals("not-declared-here", reference.alias().value());
    }

    @Test
    void anchorsFixedVersionFailuresToScalarAndNestedMemberPaths() {
        assertFailure("""
                [platforms]
                "org.example:scalar" = "LATEST"
                """, "Invalid value for `platforms.org.example:scalar`: Invalid platform version");
        assertFailure("""
                [platforms]
                "org.example:inline" = { version = "LATEST" }
                """, "Invalid value for `platforms.org.example:inline.version`: Invalid platform version");
    }

    @Test
    void anchorsInvalidVersionReferencesToTheirNestedMemberPath() {
        assertFailure("""
                [platforms]
                "org.example:platform" = { versionRef = "Bad_Id" }
                """, "Invalid value for `platforms.org.example:platform.versionRef`: Invalid local ID");
    }

    @Test
    void leavesCoordinateAndClosedUnionShapeToTheSchema() {
        assertFailure("""
                [platforms]
                "org.example:platform:tests" = "1.0.0"
                """, "Invalid dynamic key `org.example:platform:tests`");
        assertFailure("""
                [platforms]
                "org.example:platform" = { version = "1.0.0", versionRef = "release" }
                """, "must declare exactly one of `version` or `versionRef`");
        assertFailure("""
                [platforms]
                "org.example:platform" = { value = "1.0.0" }
                """, "Unknown manifest field `platforms.org.example:platform.value`");
    }

    private static AuthoredPlatforms decode(String source) {
        return new ManifestPlatformsDecoder()
                .decode(ManifestSemanticTestSupport.index(source))
                .orElseThrow();
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
