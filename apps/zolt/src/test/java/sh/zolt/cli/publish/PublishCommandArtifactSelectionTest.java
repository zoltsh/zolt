package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestPackageEvidence;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandArtifactSelectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishDryRunSelectsSpringBootWarArtifactExplicitly() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-spring-boot-war");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [package]
                mode = "spring-boot-war"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Path artifact = projectDir.resolve("target/demo-0.1.0.war");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "fake spring boot war\n");
        Files.writeString(projectDir.resolve("target/demo-0.1.0.war.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/demo-0.1.0.war",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Artifact: spring-boot-war"));
        assertTrue(result.stdout().contains("Artifact path: target/demo-0.1.0.war"));
        assertTrue(result.stdout().contains("Artifact upload path: com/example/demo/0.1.0/demo-0.1.0.war"));
        assertTrue(result.stdout().contains("Evidence: target/demo-0.1.0.war.zolt-package.json"));
        assertTrue(result.stdout().contains("Generated POM: target/publish/demo-0.1.0.pom"));
        assertTrue(result.stdout().contains("POM upload path: com/example/demo/0.1.0/demo-0.1.0.pom"));
        assertTrue(result.stdout().contains("Status: ready"));
        assertEquals("", result.stderr());
    }

    @Test
    void publishDryRunRejectsArtifactSelectorThatDoesNotMatchPackageMode() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-selector-mismatch");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Publish artifact selector `spring-boot-war` requires [package].mode = \"spring-boot-war\""));
        assertTrue(result.stderr().contains("current package mode is `thin`"));
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [dependencies.test]
                """);
    }
}
