package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §4.5 "Command discovery": {@code zolt publish --context release} run inside a workspace
 * member evaluates that member against the workspace root, so the release policy reads an effective
 * project whose version and group may be inherited from {@code [workspace.project]}.
 */
final class PublishReleasePolicyServiceTest {
    private final PublishReleasePolicyService service = new PublishReleasePolicyService();

    @TempDir
    private Path tempDir;

    @Test
    void appliesReleasePolicyToAWorkspaceMemberWithInheritedIdentity() throws IOException {
        Path member = workspace("0.1.0", """
                [project]
                name = "api"
                description = "Release-ready member."
                url = "https://example.com/api"
                issues = "https://github.com/example/api/issues"
                license = "Apache-2.0"

                [project.scm]
                url = "https://github.com/example/api"

                [project.developers.maintainer]
                name = "Example Maintainer"
                email = "maintainer@example.com"

                [dependencies]
                "sh.zolt:core" = { workspace = true }

                [package]
                sources = true
                javadoc = true
                """);

        PublishDryRunPlan plan = service.apply(member, releasePlan());

        assertEquals("release", plan.context());
        assertEquals(List.of(), plan.blockers());
    }

    @Test
    void reportsTheInheritedSnapshotVersionAsAReleaseBlocker() throws IOException {
        Path member = workspace("0.1.0-SNAPSHOT", """
                [project]
                name = "api"
                description = "Snapshot member."
                url = "https://example.com/api"
                issues = "https://github.com/example/api/issues"
                license = "Apache-2.0"

                [project.scm]
                url = "https://github.com/example/api"

                [project.developers.maintainer]
                name = "Example Maintainer"
                email = "maintainer@example.com"

                [package]
                sources = true
                javadoc = true
                """);

        PublishDryRunPlan plan = service.apply(member, releasePlan());

        assertTrue(
                plan.blockers().stream().anyMatch(blocker -> blocker.contains("0.1.0-SNAPSHOT")),
                () -> "expected the inherited version in the blockers: " + plan.blockers());
    }

    private Path workspace(String version, String memberSource) throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["modules/*"]

                [workspace.project]
                group = "sh.zolt"
                version = "%s"
                java = 21
                """.formatted(version));
        Path core = root.resolve("modules/core");
        Files.createDirectories(core);
        Files.writeString(core.resolve("zolt.toml"), """
                [project]
                name = "core"
                """);
        Path member = root.resolve("modules/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), memberSource);
        return member;
    }

    private static PublishDryRunPlan releasePlan() {
        return new PublishDryRunPlan(
                "sh.zolt:api:0.1.0",
                "release",
                "central",
                "https://central.sonatype.com",
                "api-0.1.0.jar",
                Path.of("target/api-0.1.0.jar"),
                "0".repeat(64),
                "sh/zolt/api/0.1.0/api-0.1.0.jar",
                List.of(
                        new PublishArtifactPlan(
                                "sources",
                                Optional.of("sources"),
                                Path.of("target/api-0.1.0-sources.jar"),
                                "1".repeat(64),
                                "sh/zolt/api/0.1.0/api-0.1.0-sources.jar"),
                        new PublishArtifactPlan(
                                "javadoc",
                                Optional.of("javadoc"),
                                Path.of("target/api-0.1.0-javadoc.jar"),
                                "2".repeat(64),
                                "sh/zolt/api/0.1.0/api-0.1.0-javadoc.jar")),
                Path.of("target/publish-evidence.json"),
                Path.of("target/api-0.1.0.pom"),
                "3".repeat(64),
                "sh/zolt/api/0.1.0/api-0.1.0.pom",
                List.of(),
                "",
                List.of(),
                false);
    }
}
