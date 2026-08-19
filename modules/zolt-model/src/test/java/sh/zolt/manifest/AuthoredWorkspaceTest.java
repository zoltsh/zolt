package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class AuthoredWorkspaceTest {
    @Test
    void representsVirtualWorkspaceMembershipAndImplicitAll() {
        ArrayList<WorkspaceMemberPattern> include = new ArrayList<>(List.of(
                pattern("apps/*"), pattern("modules/*")));
        AuthoredWorkspace workspace = new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(
                        include, List.of(pattern("modules/experimental")), Optional.empty()),
                Optional.empty());
        include.add(pattern("tools/*"));

        assertEquals("platform", workspace.name().value());
        assertEquals(
                List.of("apps/*", "modules/*"),
                workspace.members().include().stream()
                        .map(WorkspaceMemberPattern::value)
                        .toList());
        assertEquals(Optional.empty(), workspace.members().defaultMembers());
        assertThrows(
                UnsupportedOperationException.class,
                () -> workspace.members().include().add(pattern("tools/*")));
    }

    @Test
    void rejectsAnExplicitEmptyDefaultSelection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredWorkspaceMembers(
                        List.of(pattern("modules/*")), List.of(), Optional.of(List.of())));
    }

    @Test
    void representsOnlyTheClosedWorkspaceProjectDefaultSet() {
        AuthoredWorkspaceProjectDefaults defaults = new AuthoredWorkspaceProjectDefaults(
                Optional.of(new ProjectGroup("com.example")),
                Optional.of(new ProjectVersion("1.4.0")),
                Optional.of(new JavaFeatureRelease(21)),
                Optional.of(new ProjectLicense.Identifier("apache-2.0")));

        assertEquals("com.example", defaults.group().orElseThrow().value());
        assertEquals(21, defaults.javaRelease().orElseThrow().value());
        assertEquals(
                "Apache-2.0",
                ((ProjectLicense.Identifier) defaults.license().orElseThrow()).id());
    }

    @Test
    void rejectsInvalidWorkspaceIdentityAndEmptyStructuralTables() {
        assertThrows(IllegalArgumentException.class, () -> new LocalId("ReleaseNotes"));
        assertThrows(IllegalArgumentException.class, () -> new LocalId("1-release"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredWorkspaceMembers(List.of(), List.of(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredWorkspaceProjectDefaults(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void rejectsDuplicateAndNonportableDefaultSelections() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredWorkspaceMembers(
                        List.of(pattern("modules/*"), pattern("modules/*")),
                        List.of(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredWorkspaceMembers(
                        List.of(pattern("modules/*")),
                        List.of(),
                        Optional.of(List.of(
                                new WorkspaceMemberPath("modules/Core"),
                                new WorkspaceMemberPath("modules/core")))));
    }

    private static WorkspaceMemberPattern pattern(String value) {
        return new WorkspaceMemberPattern(value);
    }
}
