package sh.zolt.cli.nativeimage;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.CliTestRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeCommandValidationTest {
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    private Path tempDir;

    @Test
    void nativeReportsMissingMainClassClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        NativeCommandTestSupport.writeProjectConfigWithoutMain(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "native",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Native Image main class is missing"));
        assertTrue(result.stderr().contains("[project].main"));
    }

    @Test
    void nativeReportsSpringBootNativeRequiresExplicitAotFlag() throws IOException {
        Path projectDir = tempDir.resolve("spring-boot-demo");
        NativeCommandTestSupport.writeSpringBootProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "native",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Spring Boot native images require `[framework.springBoot.native] enabled = true`"));
        assertTrue(result.stderr().contains("Spring Boot JVM build, test, run, and executable packaging"));
        assertTrue(result.stderr().contains("explicit Zolt-owned Spring Boot AOT/native canary path"));
        assertTrue(result.stderr().contains("zolt package --mode spring-boot"));
        assertFalse(result.stderr().contains("not supported by Zolt yet"));
    }

    @Test
    void nativeReportsMissingSpringBootAotToolingClearly() throws IOException {
        Path projectDir = tempDir.resolve("spring-boot-native-demo");
        NativeCommandTestSupport.writeExplicitSpringBootNativeProjectConfig(
                projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "native",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Spring Boot native AOT requires tool artifact"));
        assertTrue(result.stderr().contains("Add the Spring Boot platform to [platforms]"));
    }

    @Test
    void nativeReportsMissingConfiguredNativeImageBeforePackaging() throws IOException {
        Path projectDir = tempDir.resolve("missing-native-image-demo");
        NativeCommandTestSupport.writeProjectConfigWithMain(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "native",
                "--native-image", projectDir.resolve("missing/native-image").toString(),
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Configured Native Image executable is not available"));
        assertTrue(result.stderr().contains("Install GraalVM Native Image"));
        assertTrue(result.stderr().contains("--native-image"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void nativeRejectsIncompleteLockBeforeInvokingNativeImage() throws IOException {
        Path projectDir = tempDir.resolve("invalid-lock-native");
        NativeCommandTestSupport.writeProjectConfigWithMain(
                projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "test"
                scope = "runtime"
                direct = false
                jar = "blobs/v2/sha256/%s/runtime-lib.jar"
                dependencies = []
                """.formatted(DIGEST));
        Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                tempDir.resolve("invalid-lock-native-image"));

        CommandResult result = execute(
                "native",
                "--native-image", nativeImage.toString(),
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("invalid-lock-cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("`jar` and `jarSha256` must be recorded together"));
        assertFalse(Files.exists(projectDir.resolve("target/native/demo")));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void nativeRejectsVersionFiveArtifactPathsBeforeInvokingNativeImage() throws IOException {
        Path projectDir = tempDir.resolve("version-five-native");
        NativeCommandTestSupport.writeProjectConfigWithMain(
                projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "test"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []
                """);
        Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                tempDir.resolve("version-five-native-image"));

        CommandResult result = execute(
                "native",
                "--native-image", nativeImage.toString(),
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("version-five-native-cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("zolt.lock version 5 predates the version 6"), result.stderr());
        assertTrue(result.stderr().contains("Run `zolt resolve` once"), result.stderr());
        assertFalse(Files.exists(projectDir.resolve("target/native/demo")));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void nativeRejectsStaleDependencyEditBeforeInvokingNativeImage() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "dependency", "1.0.0", pom("1.0.0"));
            repository.addArtifact("com.example", "dependency", "2.0.0", pom("2.0.0"));
            Path projectDir = tempDir.resolve("stale-native");
            NativeCommandTestSupport.writeProjectConfigWithMain(
                    projectDir, repository.baseUri().toString());
            Files.writeString(
                    projectDir.resolve("zolt.toml"),
                    Files.readString(projectDir.resolve("zolt.toml"))
                            .replace("[dependencies]\n", "[dependencies]\n\"com.example:dependency\" = \"1.0.0\"\n"));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("stale-native-cache").toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.writeString(
                    projectDir.resolve("zolt.toml"),
                    Files.readString(projectDir.resolve("zolt.toml"))
                            .replace("\"1.0.0\"", "\"2.0.0\""));
            Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                    tempDir.resolve("stale-native-image"));

            CommandResult result = execute(
                    "native",
                    "--native-image", nativeImage.toString(),
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("stale-native-cache").toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("zolt.lock is out of date"), result.stderr());
            assertFalse(Files.exists(projectDir.resolve("target/native/demo")));
            assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
        }
    }

    private static String pom(String version) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>dependency</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version);
    }
}
