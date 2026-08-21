package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestPackageEvidence;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandPomTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishDryRunGeneratesPomMetadataForDirectDependencies() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-pom-metadata");
        Files.createDirectories(projectDir.resolve("target"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-pom-metadata") + """
                description = "Dependency metadata fixture for publish dry run."
                url = "https://example.com/publish-metadata"

                [dependencies]
                "org.example:api" = { version = "1.2.3", optional = true, exclude = ["org.legacy:bad-lib"] }
                "org.example:publish-helper" = { version = "2.0.0", publishOnly = true }

                [dependencies.runtime]
                "org.example:runtime" = "3.0.0"

                [dependencies.provided]
                "jakarta.servlet:jakarta.servlet-api" = "6.1.0"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.example:api"
                version = "1.2.3"
                lane = "implementation"
                optional = true
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "."
                id = "org.example:runtime"
                version = "3.0.0"
                lane = "runtime"
                resolvedScope = "runtime"

                [[dependencyRoot]]
                member = "."
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "."
                id = "org.example:publish-helper"
                version = "2.0.0"
                lane = "implementation"
                publishOnly = true

                [[package]]
                id = "org.example:api"
                version = "1.2.3"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/api/1.2.3/api-1.2.3.jar"
                dependencies = []

                [[package]]
                id = "org.example:runtime"
                version = "3.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "org/example/runtime/3.0.0/runtime-3.0.0.jar"
                dependencies = []

                [[package]]
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
                dependencies = []
                """);
        Path artifact = projectDir.resolve("target/publish-dry-run-pom-metadata-0.1.0.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-pom-metadata-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-pom-metadata-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        CliTestPackageEvidence.write(projectDir);

        CommandResult result = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        Path pom = projectDir.resolve("target/publish/publish-dry-run-pom-metadata-0.1.0.pom");
        String pomXml = Files.readString(pom);
        assertTrue(result.stdout().contains("Generated POM: target/publish/publish-dry-run-pom-metadata-0.1.0.pom"));
        assertTrue(pomXml.contains("<name>Publish Metadata Fixture</name>"));
        assertTrue(pomXml.contains("<groupId>org.example</groupId>"));
        assertTrue(pomXml.contains("<artifactId>api</artifactId>"));
        assertTrue(pomXml.contains("<optional>true</optional>"));
        assertTrue(pomXml.contains("<groupId>org.legacy</groupId>"));
        assertTrue(pomXml.contains("<artifactId>bad-lib</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>runtime</artifactId>"));
        assertTrue(pomXml.contains("<scope>runtime</scope>"));
        assertTrue(pomXml.contains("<artifactId>jakarta.servlet-api</artifactId>"));
        assertTrue(pomXml.contains("<scope>provided</scope>"));
        assertTrue(pomXml.contains("<artifactId>publish-helper</artifactId>"));
        assertFalse(pomXml.contains("<scope>test</scope>"));
        assertEquals("", result.stderr());
    }

    @Test
    void publishDryRunProducesDeterministicOutputAndPom() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-deterministic");
        Files.createDirectories(projectDir.resolve("target"));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-dry-run-deterministic") + """

                [dependencies]
                "org.example:zeta" = "2.0.0"
                "org.example:alpha" = "1.0.0"

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.example:zeta"
                version = "2.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "."
                id = "org.example:alpha"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.example:zeta"
                version = "2.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/zeta/2.0.0/zeta-2.0.0.jar"
                dependencies = []

                [[package]]
                id = "org.example:alpha"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/alpha/1.0.0/alpha-1.0.0.jar"
                dependencies = []
                """);
        Path artifact = projectDir.resolve("target/publish-dry-run-deterministic-0.1.0.jar");
        Files.writeString(artifact, "fake deterministic package\n");
        Files.writeString(projectDir.resolve("target/publish-dry-run-deterministic-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-dry-run-deterministic-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        CliTestPackageEvidence.write(projectDir);

        CommandResult first = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());
        String firstPom = Files.readString(projectDir.resolve("target/publish/publish-dry-run-deterministic-0.1.0.pom"));
        CommandResult second = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());
        String secondPom = Files.readString(projectDir.resolve("target/publish/publish-dry-run-deterministic-0.1.0.pom"));

        assertEquals(0, first.exitCode());
        assertEquals(0, second.exitCode());
        assertEquals(first.stdout(), second.stdout());
        assertEquals(firstPom, secondPom);
        int alphaIndex = firstPom.indexOf("<artifactId>alpha</artifactId>");
        int zetaIndex = firstPom.indexOf("<artifactId>zeta</artifactId>");
        assertTrue(alphaIndex >= 0);
        assertTrue(zetaIndex > alphaIndex);
        assertTrue(first.stdout().contains("Status: ready"));
        assertEquals("", first.stderr());
        assertEquals("", second.stderr());
    }
}
