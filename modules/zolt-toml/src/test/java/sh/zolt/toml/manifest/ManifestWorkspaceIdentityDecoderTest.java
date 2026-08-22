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
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.toml.ZoltConfigException;

final class ManifestWorkspaceIdentityDecoderTest {
    @Test
    void decodesEveryWorkspaceIdentityFieldWithoutApplyingWorkspaceRules() {
        AuthoredWorkspace workspace = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["apps/api", "modules/core"]
                include = ["modules/*", "apps/*"]
                exclude = ["modules/experimental", "apps/old"]

                [workspace.project]
                group = "com.example"
                version = "1.4.0"
                java = 21
                license = { id = "Apache-2.0 OR MIT", name = "Apache or MIT" }
                """);

        assertEquals("platform", workspace.name().value());
        assertEquals(
                List.of("apps/*", "modules/*"),
                workspace.members().include().stream().map(Object::toString).toList());
        assertEquals(
                List.of("apps/old", "modules/experimental"),
                workspace.members().exclude().stream().map(Object::toString).toList());
        assertEquals(
                List.of("apps/api", "modules/core"),
                workspace.members().defaultMembers().orElseThrow().stream()
                        .map(Object::toString)
                        .toList());
        var defaults = workspace.projectDefaults().orElseThrow();
        assertEquals("com.example", defaults.group().orElseThrow().value());
        assertEquals("1.4.0", defaults.version().orElseThrow().value());
        assertEquals(21, defaults.javaRelease().orElseThrow().value());
        ProjectLicense.Metadata license = assertInstanceOf(
                ProjectLicense.Metadata.class, defaults.license().orElseThrow());
        assertEquals("Apache-2.0 OR MIT", license.id().orElseThrow());
    }

    @Test
    void preservesOmittedDefaultsAndAcceptsExplicitEmptyExclude() {
        AuthoredWorkspace workspace = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["modules/*"]
                exclude = []
                """);

        assertEquals(Optional.empty(), workspace.members().defaultMembers());
        assertTrue(workspace.members().exclude().isEmpty());
        assertEquals(Optional.empty(), workspace.projectDefaults());
        assertFalse(decode("").workspace().isPresent());
    }

    @Test
    void returnsBothAuthoredDomainsWhenOneManifestIsARootProjectWorkspace() {
        ManifestIdentityDecoder.Decoded identity = decode("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = ["."]

                [project]
                name = "platform-root"
                """);

        assertEquals("platform", identity.workspace().orElseThrow().name().value());
        assertEquals("platform-root", identity.project().orElseThrow().identity().name().value());
    }

    @Test
    void requiresWorkspaceNameMembershipTableAndIncludeInThatOrder() {
        assertFailure("""
                [workspace]
                name = "platform"
                """, "Missing required manifest section `[workspace.members]`.");
        assertFailure("""
                [workspace]
                name = "platform"

                [workspace.members]
                exclude = []
                """, "Missing required manifest field `workspace.members.include`.");
        assertFailure("""
                [workspace.members]
                include = ["modules/*"]
                """, "Missing required manifest field `workspace.name`.");
    }

    @ParameterizedTest
    @MethodSource("invalidMembershipAggregates")
    void anchorsMembershipAggregateFailuresToTheFieldThatIntroducesThem(
            String body,
            String field,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> workspace("""
                        [workspace]
                        name = "platform"

                        [workspace.members]
                        """ + body));

        assertTrue(failure.getMessage().contains("Invalid value for `" + field + "`"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
    }

    static Stream<Arguments> invalidMembershipAggregates() {
        return Stream.of(
                Arguments.of("include = []\n", "workspace.members.include", "must not be empty"),
                Arguments.of(
                        "include = [\"modules/*\", \"modules/*\"]\n",
                        "workspace.members.include",
                        "duplicate"),
                Arguments.of(
                        "include = [\"modules/*\"]\nexclude = [\"legacy\", \"legacy\"]\n",
                        "workspace.members.exclude",
                        "duplicate"),
                Arguments.of(
                        "include = [\"modules/*\"]\ndefault = []\n",
                        "workspace.members.default",
                        "must not be empty"),
                Arguments.of(
                        "include = [\"modules/*\"]\ndefault = [\"modules/Core\", \"modules/core\"]\n",
                        "workspace.members.default",
                        "Unicode portability"));
    }

    @Test
    void rejectsJavaReleaseOverflowAtItsExactWorkspaceField() {
        assertFailure("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                java = 9223372036854775807
                """, "Invalid value for `workspace.project.java`: Java feature release is outside");
    }

    @Test
    void reportsInvalidWorkspaceNamesAtTheSchemaOwnedFieldPath() {
        assertFailure("""
                [workspace]
                name = "Bad_Name"

                [workspace.members]
                include = ["modules/*"]
                """, "Invalid value for `workspace.name`");
        assertFailure("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                group = "com/example"
                """, "Invalid value for `workspace.project.group`");
    }

    @Test
    void defersMembershipExpansionAndEffectiveProjectRequirements() {
        AuthoredWorkspace workspace = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["not-yet-discovered"]
                include = ["modules/*"]
                exclude = ["modules/*"]

                [workspace.project]
                group = "com.example"
                """);

        assertEquals(
                "not-yet-discovered",
                workspace.members().defaultMembers().orElseThrow().getFirst().value());
        assertTrue(workspace.projectDefaults().orElseThrow().version().isEmpty());
    }

    @Test
    void defersWhetherRootMembershipHasACorrespondingProjectDomain() {
        ManifestIdentityDecoder.Decoded identity = decode("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = ["."]
                """);
        AuthoredWorkspace workspace = identity.workspace().orElseThrow();

        assertEquals(".", workspace.members().include().getFirst().value());
        assertFalse(identity.project().isPresent());
    }

    private static AuthoredWorkspace workspace(String source) {
        return decode(source).workspace().orElseThrow();
    }

    private static ManifestIdentityDecoder.Decoded decode(String source) {
        return new ManifestIdentityDecoder().decode(ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
