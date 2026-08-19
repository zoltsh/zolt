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
        ArrayList<String> include = new ArrayList<>(List.of("apps/*", "modules/*"));
        AuthoredWorkspace workspace = new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(include, List.of("modules/experimental"), Optional.empty()),
                Optional.empty());
        include.add("tools/*");

        assertEquals("platform", workspace.name().value());
        assertEquals(List.of("apps/*", "modules/*"), workspace.members().include());
        assertEquals(Optional.empty(), workspace.members().defaultMembers());
        assertThrows(
                UnsupportedOperationException.class,
                () -> workspace.members().include().add("tools/*"));
    }

    @Test
    void preservesAnExplicitEmptyDefaultSelection() {
        AuthoredWorkspaceMembers members =
                new AuthoredWorkspaceMembers(List.of("modules/*"), List.of(), Optional.of(List.of()));

        assertEquals(Optional.of(List.of()), members.defaultMembers());
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
}
