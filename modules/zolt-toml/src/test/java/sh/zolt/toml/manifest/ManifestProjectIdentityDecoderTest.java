package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.toml.ZoltConfigException;

final class ManifestProjectIdentityDecoderTest {
    @Test
    void decodesEveryProjectIdentityAndMetadataFieldFromNamedDeveloperSections() {
        AuthoredProject project = project("""
                [project]
                name = "example-library"
                version = "1.0.0"
                group = "com.example"
                java = 21
                main = "com.example.Main$Nested"
                description = "  A reusable library.  "
                url = "https://EXAMPLE.com/Library/"
                issues = "https://example.com/issues"
                license = "MIT"

                [project.scm]
                url = "https://example.com/source"
                connection = "scm:git:https://example.com/source.git"
                developerConnection = "scm:git:ssh://git@example.com/source.git"
                tag = "v1.0.0"

                [project.developers.grace]
                name = "Grace Hopper"
                organization = "US Navy"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"
                url = "https://example.com/ada"
                """);

        assertEquals("example-library", project.identity().name().value());
        assertEquals("1.0.0", project.identity().version().orElseThrow().value());
        assertEquals("com.example", project.identity().group().orElseThrow().value());
        assertEquals(21, project.identity().javaRelease().orElseThrow().value());
        assertEquals("com.example.Main$Nested", project.metadata().main().orElseThrow().value());
        assertEquals("  A reusable library.  ", project.metadata().description().orElseThrow());
        assertEquals("https://EXAMPLE.com/Library/", project.metadata().url().orElseThrow());
        assertEquals(
                "scm:git:ssh://git@example.com/source.git",
                project.metadata().scm().orElseThrow().developerConnection().orElseThrow());
        assertEquals(
                List.of(new LocalId("ada"), new LocalId("grace")),
                new ArrayList<>(project.metadata().developers().keySet()));
        assertEquals(
                "US Navy",
                project.metadata().developers().get(new LocalId("grace"))
                        .organization()
                        .orElseThrow());
    }

    @Test
    void preservesCompactProjectOmissionsAndExplicitEmptyDeveloperCollection() {
        AuthoredProject project = project("""
                [project]
                name = "orders-core"

                [project.developers]
                """);

        assertEquals(Optional.empty(), project.identity().version());
        assertEquals(Optional.empty(), project.identity().group());
        assertEquals(Optional.empty(), project.identity().javaRelease());
        assertEquals(Optional.empty(), project.identity().license());
        assertEquals(Optional.empty(), project.metadata().main());
        assertEquals(Optional.empty(), project.metadata().scm());
        assertTrue(project.metadata().developers().isEmpty());
        assertFalse(decode("").project().isPresent());
    }

    @Test
    void requiresProjectNameEvenWhenOnlyNestedMetadataIntroducesTheDomain() {
        assertFailure("""
                [project.scm]
                url = "https://example.com/source"
                """, "Missing required manifest field `project.name`.");
        assertFailure("""
                [project.developers.ada]
                name = "Ada Lovelace"
                """, "Missing required manifest field `project.name`.");
    }

    @Test
    void anchorsScalarModelFailuresToTheirExactFields() {
        assertFailure("""
                [project]
                name = "bad:name"
                """, "Invalid value for `project.name`");
        assertFailure("""
                [project]
                name = "demo"
                group = "com/example"
                """, "Invalid value for `project.group`");
        assertFailure("""
                [project]
                name = "demo"
                version = "[1.0,2.0)"
                """, "Invalid value for `project.version`");
        assertFailure("""
                [project]
                name = "demo"
                main = "Main"
                """, "Invalid value for `project.main`");
        assertFailure("""
                [project]
                name = "demo"
                description = "  "
                """, "Invalid value for `project.description`: Project description must not be blank");
        assertFailure("""
                [project]
                name = "demo"

                [project.scm]
                connection = "\\t"
                """, "Invalid value for `project.scm.connection`");
        assertFailure("""
                [project]
                name = "demo"

                [project.developers.ada]
                email = ""
                """, "Invalid value for `project.developers.ada.email`");
    }

    @Test
    void rejectsJavaReleaseSignAndOverflowAtTheProjectField() {
        assertFailure("""
                [project]
                name = "demo"
                java = 0
                """, "Invalid value for `project.java`: Java feature release must be a positive integer");
        assertFailure("""
                [project]
                name = "demo"
                java = -9223372036854775808
                """, "Invalid value for `project.java`: Java feature release is outside");
    }

    @Test
    void defersStandaloneAndBomEffectiveRequirements() {
        AuthoredProject project = project("""
                [project]
                name = "catalog"

                [bom]
                members = ["modules/core"]
                """);

        assertTrue(project.identity().version().isEmpty());
        assertTrue(project.identity().group().isEmpty());
        assertTrue(project.identity().javaRelease().isEmpty());
    }

    private static AuthoredProject project(String source) {
        return decode(source).project().orElseThrow();
    }

    private static DecodedManifestIdentity decode(String source) {
        return new ManifestIdentityDecoder().decode(ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
