package sh.zolt.workspace.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.WorkspaceConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkspaceTomlWriterTest {
    private final WorkspaceTomlWriter writer = new WorkspaceTomlWriter();
    private final WorkspaceConfigParser parser = new WorkspaceConfigParser();

    @Test
    void writesMultiMemberArraysOneEntryPerLine() {
        String toml = writer.write(new WorkspaceConfig(
                "acme-platform",
                List.of("apps/api", "modules/core"),
                List.of("apps/api", "modules/core"),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of()));

        assertEquals("""
                [workspace]
                name = "acme-platform"
                members = [
                    "apps/api",
                    "modules/core",
                ]
                defaultMembers = [
                    "apps/api",
                    "modules/core",
                ]

                [repositories]
                "central" = "https://repo.maven.apache.org/maven2"
                """, toml);

        WorkspaceConfig parsed = parser.parse(toml);
        assertEquals(List.of("apps/api", "modules/core"), parsed.members());
        assertEquals(List.of("apps/api", "modules/core"), parsed.defaultMembers());
    }

    @Test
    void keepsSingleMemberArraysCompact() {
        String toml = writer.write(new WorkspaceConfig(
                "app",
                List.of("apps/app"),
                List.of("apps/app"),
                Map.of(),
                Map.of()));

        assertEquals("""
                [workspace]
                name = "app"
                members = ["apps/app"]
                defaultMembers = ["apps/app"]
                """, toml);
    }

    @Test
    void patchesOnlyWorkspaceRootPlatformValue() {
        String source = """
                # root policy
                [workspace]
                name = "demo"
                members = ["apps/api"]

                [platforms]
                "org.junit:junit-bom" = "5.10.2" # keep this note

                [commands.verify]
                run = ["zolt", "check"]
                """;
        WorkspaceConfig original = parser.parseRootConfig(source);
        WorkspaceConfig updated = original.withPlatforms(Map.of("org.junit:junit-bom", "5.11.4"));

        WorkspaceManifestDocument patched = writer.patchDocument(
                new WorkspaceManifestDocument(source, original, true), updated);

        assertTrue(patched.source().startsWith("# root policy\n"));
        assertTrue(patched.source().contains(
                "\"org.junit:junit-bom\" = \"5.11.4\" # keep this note"));
        assertTrue(patched.source().contains("[commands.verify]\nrun = [\"zolt\", \"check\"]"));
        assertEquals(updated, parser.parseRootConfig(patched.source()));
    }

    @Test
    void patchesLegacyWorkspacePlatformWithoutChangingCrLf() {
        String source = "[workspace]\r\n"
                + "name = \"demo\"\r\n"
                + "members = [\"apps/api\"]\r\n\r\n"
                + "[platforms]\r\n"
                + "\"org.junit:junit-bom\" = \"5.10.2\"\r\n";
        WorkspaceConfig original = parser.parse(source);
        WorkspaceConfig updated = original.withPlatforms(Map.of("org.junit:junit-bom", "5.11.4"));

        WorkspaceManifestDocument patched = writer.patchDocument(
                new WorkspaceManifestDocument(source, original, false), updated);

        assertEquals(source.replace("5.10.2", "5.11.4"), patched.source());
        assertEquals(updated, parser.parse(patched.source()));
    }

    @Test
    void recognizesAssignmentAndCommentMarkersOnlyOutsideQuotedText() {
        String source = """
                [workspace]
                name = "demo"
                members = ["apps/api"]

                [platforms]
                'org.junit:junit=bom' = '5.10.2#old' # keep this note
                """;
        WorkspaceConfig original = parser.parseRootConfig(source);
        WorkspaceConfig updated = original.withPlatforms(Map.of("org.junit:junit=bom", "5.11.4"));

        WorkspaceManifestDocument patched = writer.patchDocument(
                new WorkspaceManifestDocument(source, original, true), updated);

        assertTrue(patched.source().contains(
                "'org.junit:junit=bom' = \"5.11.4\" # keep this note"));
        assertEquals(updated, parser.parseRootConfig(patched.source()));
    }
}
