package sh.zolt.cli.supplychain;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LicensesCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void groupsResolvedLicensesAndWritesNotices() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cache = tempDir.resolve("cache");
        writeProject(projectDir, cache, "Apache License, Version 2.0");

        CommandResult text = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, text.exitCode(), text.stderr());
        assertTrue(text.stdout().contains("Apache-2.0 (1)"), text.stdout());
        assertTrue(text.stdout().contains("org.example:lib:1.0.0"), text.stdout());

        Path noticesFile = projectDir.resolve("THIRD_PARTY.txt");
        CommandResult json = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString(),
                "--format", "json",
                "--notices", noticesFile.toString());
        assertEquals(0, json.exitCode(), json.stderr());
        assertTrue(json.stdout().contains("\"license\": \"Apache-2.0\""), json.stdout());
        assertTrue(json.stdout().contains("\"status\": \"spdx\""), json.stdout());
        assertTrue(Files.readString(noticesFile).contains("org.example:lib:1.0.0"), "notices lists the dependency");
    }

    private static void writeProject(Path projectDir, Path cache, String licenseName) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo"));
        Artifact jar = writeArtifact(cache, "lib-1.0.0.jar", "license command jar\n");
        Artifact pom = writeArtifact(cache, "lib-1.0.0.pom", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>%s</name></license></licenses>
                </project>
                """.formatted(licenseName));
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7
                projectResolutionFingerprint = "sha256:cli-licenses"

                [[dependencyRoot]]
                member = "."
                id = "org.example:lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "%s"
                pom = "%s"
                jarSha256 = "%s"
                pomSha256 = "%s"
                dependencies = []
                """.formatted(jar.relative(), pom.relative(), jar.sha256(), pom.sha256()));
    }

    private static Artifact writeArtifact(Path cache, String fileName, String content) throws IOException {
        Path staged = cache.resolve("staging").resolve(fileName);
        Files.createDirectories(staged.getParent());
        Files.writeString(staged, content);
        String digest = sha256(staged);
        String relative = "blobs/v2/sha256/" + digest + "/" + fileName;
        Path target = cache.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        return new Artifact(relative, digest);
    }

    private record Artifact(String relative, String sha256) {}
}
