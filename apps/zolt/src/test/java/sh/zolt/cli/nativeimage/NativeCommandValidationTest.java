package sh.zolt.cli.nativeimage;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
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
        assertTrue(result.stderr().contains("Spring Boot native images require `[framework.spring-boot] native = true`"));
        assertTrue(result.stderr().contains("Spring Boot JVM build, test, run, and executable packaging"));
        assertTrue(result.stderr().contains("explicit Zolt-owned Spring Boot AOT/native canary path"));
        assertTrue(result.stderr().contains("[package].mode = \"spring-boot\""));
        assertTrue(result.stderr().contains("zolt resolve"));
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
                version = 7

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
        assertTrue(
                result.stderr().contains("zolt.lock version 5 is older than this Zolt supports (current 7)"),
                result.stderr());
        assertTrue(result.stderr().contains("Run `zolt resolve` with this Zolt version"), result.stderr());
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

    @Test
    void persistentSpringBootPackageAndNativeCommandsShareOneStableLock() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact(
                    "org.springframework.boot",
                    "spring-boot-dependencies",
                    "3.3.6",
                    springBootPlatformPom());
            repository.addArtifact(
                    "org.springframework.boot",
                    "spring-boot-loader",
                    "3.3.6",
                    pom("org.springframework.boot", "spring-boot-loader", "3.3.6"),
                    NativeCommandTestSupport.fakeSpringBootLoaderJar());
            repository.addArtifact(
                    "org.springframework.boot",
                    "spring-boot",
                    "3.3.6",
                    pom("org.springframework.boot", "spring-boot", "3.3.6"),
                    NativeCommandTestSupport.fakeSpringBootAotJar(tempDir.resolve("fake-spring-aot")));
            Path projectDir = tempDir.resolve("stable-spring-native");
            Path cacheRoot = tempDir.resolve("stable-spring-cache");
            NativeCommandTestSupport.writePersistentSpringBootNativeProjectConfig(
                    projectDir,
                    repository.baseUri().toString());
            CommandResult resolved = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            assertEquals(0, resolved.exitCode(), resolved.stderr());
            Path lockfile = projectDir.resolve("zolt.lock");
            String stableLock = Files.readString(lockfile);
            var locked = new ZoltLockfileReader().read(lockfile);
            assertEquals(1, locked.packages().stream()
                    .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(
                            "org.springframework.boot", "spring-boot-loader")))
                    .count());
            var loader = locked.packages().stream()
                    .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(
                            "org.springframework.boot", "spring-boot-loader")))
                    .filter(lockPackage -> lockPackage.scope() == DependencyScope.RUNTIME)
                    .findFirst()
                    .orElseThrow();
            assertFalse(loader.direct(), loader.toString());
            assertTrue(SpringBootLoaderArtifact.isDefaultLoader(loader), loader.toString());
            assertTrue(locked.packages().stream().anyMatch(lockPackage ->
                    lockPackage.packageId().equals(new PackageId(
                            "org.springframework.boot", "spring-boot"))
                            && lockPackage.scope() == DependencyScope.TOOL_SPRING_AOT));
            Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                    tempDir.resolve("stable-spring-native-image"));

            CommandResult firstNative = nativeCommand(projectDir, cacheRoot, nativeImage);
            assertEquals(0, firstNative.exitCode(), firstNative.stderr());
            assertEquals(stableLock, Files.readString(lockfile));
            String nativeLog = Files.readString(projectDir.resolve("target/native/native-image.log"));
            assertFalse(nativeLog.contains("spring-boot-loader"), nativeLog);
            assertTrue(nativeLog.contains("spring-aot/main/classes"), nativeLog);

            CommandResult packaged = execute(
                    "package",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            assertEquals(0, packaged.exitCode(), packaged.stderr());
            assertEquals(stableLock, Files.readString(lockfile));

            CommandResult secondNative = nativeCommand(projectDir, cacheRoot, nativeImage);
            assertEquals(0, secondNative.exitCode(), secondNative.stderr());
            assertEquals(stableLock, Files.readString(lockfile));
        }
    }

    private static CommandResult nativeCommand(Path projectDir, Path cacheRoot, Path nativeImage) {
        return execute(
                "native",
                "--native-image", nativeImage.toString(),
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());
    }

    private static String springBootPlatformPom() {
        return """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-dependencies</artifactId>
                  <version>3.3.6</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-loader</artifactId>
                        <version>3.3.6</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot</artifactId>
                        <version>3.3.6</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
    }

    private static String pom(String version) {
        return pom("com.example", "dependency", version);
    }

    private static String pom(String group, String artifact, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }
}
