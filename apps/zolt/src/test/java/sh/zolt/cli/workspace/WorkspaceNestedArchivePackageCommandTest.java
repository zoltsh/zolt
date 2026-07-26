package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class WorkspaceNestedArchivePackageCommandTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedModes")
    void nestedArchiveMaterializesThinWorkspaceProviderDeterministically(
            NestedMode mode) throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifact(repository, "org.example", "runtime-lib", "1.0.0");
            if (mode.springBoot()) {
                addArtifact(
                        repository,
                        "org.springframework.boot",
                        "spring-boot-loader",
                        "4.0.6",
                        jarBytes(mode == NestedMode.SPRING_BOOT
                                ? "org/springframework/boot/loader/launch/JarLauncher.class"
                                : "org/springframework/boot/loader/launch/WarLauncher.class"));
            }
            Path workspace = writeWorkspace(repository, mode);
            Path cache = tempDir.resolve("cache-" + mode.configValue());

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Path archive = workspace.resolve(
                    "apps/web/target/web-0.1.0." + mode.extension());
            CommandResult firstPackage = packageMember(workspace, cache);
            assertEquals(
                    0,
                    firstPackage.exitCode(),
                    firstPackage.stdout() + firstPackage.stderr());
            byte[] firstBytes = Files.readAllBytes(archive);

            String providerEntry =
                    mode.libraryPrefix() + "com.example-provider-0.1.0.jar";
            String runtimeEntry =
                    mode.libraryPrefix() + "runtime-lib-1.0.0.jar";
            try (JarFile consumer = new JarFile(archive.toFile())) {
                JarEntry provider = consumer.getJarEntry(providerEntry);
                assertNotNull(provider);
                assertNotNull(consumer.getJarEntry(runtimeEntry));
                assertTrue(consumer.getJarEntry(
                        mode.libraryPrefix() + "classes") == null);
                Path nested = tempDir.resolve(
                        mode.configValue() + "-provider.jar");
                Files.write(nested, consumer.getInputStream(provider).readAllBytes());
                try (JarFile providerJar = new JarFile(nested.toFile())) {
                    assertNotNull(providerJar.getJarEntry(
                            "com/example/provider/Provider.class"));
                }
            }
            String evidence = Files.readString(
                    archive.resolveSibling(
                            archive.getFileName() + ".zolt-package.json"));
            assertTrue(evidence.contains(
                    "\"location\": \"" + providerEntry + "\""), evidence);
            assertTrue(evidence.contains(
                    "\"coordinate\": \"com.example:provider:0.1.0\""), evidence);

            CommandResult secondPackage = packageMember(workspace, cache);
            assertEquals(
                    0,
                    secondPackage.exitCode(),
                    secondPackage.stdout() + secondPackage.stderr());
            assertArrayEquals(firstBytes, Files.readAllBytes(archive));
        }
    }

    @Test
    void materializedRuntimeInputUsesAStableJarNameInsteadOfClasses()
            throws IOException {
        Path directory = tempDir.resolve("classes");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("Example.class"), "class-bytes");

        sh.zolt.build.packaging.PackageRuntimeJar runtimeJar =
                new sh.zolt.build.packaging.PackageRuntimeJar(
                        new sh.zolt.dependency.PackageId(
                                "com.example",
                                "provider"),
                        "1.2.3",
                        directory);

        assertEquals(
                "com.example-provider-1.2.3.jar",
                sh.zolt.build.packaging.PackageRuntimeJars
                        .nestedJarName(runtimeJar));
    }

    private Path writeWorkspace(
            CliTestRepository repository,
            NestedMode mode) throws IOException {
        Path workspace = tempDir.resolve("workspace-" + mode.configValue());
        Path provider = workspace.resolve("modules/provider");
        Path app = workspace.resolve("apps/web");
        Files.createDirectories(provider.resolve(
                "src/main/java/com/example/provider"));
        Files.createDirectories(app.resolve(
                "src/main/java/com/example/web"));
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "nested-%s"
                members = ["modules/provider", "apps/web"]

                [repositories]
                test = "%s"
                """.formatted(mode.configValue(), repository.baseUri()));
        Files.writeString(provider.resolve("zolt.toml"), project("provider")
                + """

                [runtime.dependencies]
                "org.example:runtime-lib" = "1.0.0"

                [build.metadata]
                reproducible = true
                """);
        Files.writeString(
                provider.resolve(
                        "src/main/java/com/example/provider/Provider.java"),
                """
                package com.example.provider;

                public final class Provider {
                    public static String message() {
                        return "provider";
                    }
                }
                """);
        Files.writeString(app.resolve("zolt.toml"), project("web")
                + """
                main = "com.example.web.Web"

                [dependencies]
                "com.example:provider" = { workspace = "modules/provider" }

                %s
                [package]
                mode = "%s"

                [build.metadata]
                reproducible = true
                """.formatted(
                        mode.springBoot()
                                ? """
                                  [runtime.dependencies]
                                  "org.springframework.boot:spring-boot-loader" = "4.0.6"

                                  """
                                : "",
                        mode.configValue()));
        Files.writeString(
                app.resolve("src/main/java/com/example/web/Web.java"),
                """
                package com.example.web;

                import com.example.provider.Provider;

                public final class Web {
                    public static void main(String[] args) {
                        System.out.println(Provider.message());
                    }
                }
                """);
        return workspace;
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                """.formatted(name, currentJavaMajorVersion());
    }

    private static CommandResult packageMember(
            Path workspace,
            Path cache) {
        return execute(
                "package",
                "--workspace",
                "--member", "apps/web",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void addArtifact(
            CliTestRepository repository,
            String group,
            String artifact,
            String version) {
        addArtifact(repository, group, artifact, version, null);
    }

    private static void addArtifact(
            CliTestRepository repository,
            String group,
            String artifact,
            String version,
            byte[] jar) {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        if (jar == null) {
            repository.addArtifact(group, artifact, version, pom);
            return;
        }
        repository.addArtifact(
                group,
                artifact,
                version,
                pom,
                jar);
    }

    private static byte[] jarBytes(String entry) throws IOException {
        java.io.ByteArrayOutputStream bytes =
                new java.io.ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry(entry));
            output.write(0);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Stream<NestedMode> nestedModes() {
        return Stream.of(NestedMode.values());
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    private enum NestedMode {
        SPRING_BOOT("spring-boot", "jar", "BOOT-INF/lib/", true),
        WAR("war", "war", "WEB-INF/lib/", false),
        SPRING_BOOT_WAR(
                "spring-boot-war",
                "war",
                "WEB-INF/lib/",
                true);

        private final String configValue;
        private final String extension;
        private final String libraryPrefix;
        private final boolean springBoot;

        NestedMode(
                String configValue,
                String extension,
                String libraryPrefix,
                boolean springBoot) {
            this.configValue = configValue;
            this.extension = extension;
            this.libraryPrefix = libraryPrefix;
            this.springBoot = springBoot;
        }

        String configValue() {
            return configValue;
        }

        String extension() {
            return extension;
        }

        String libraryPrefix() {
            return libraryPrefix;
        }

        boolean springBoot() {
            return springBoot;
        }
    }
}
