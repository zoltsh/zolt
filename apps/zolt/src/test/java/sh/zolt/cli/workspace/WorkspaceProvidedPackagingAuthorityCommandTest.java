package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.build.packageevidence.PackageEvidenceManifest;
import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceProvidedPackagingAuthorityCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void unrelatedMemberCannotMoveRuntimeArtifactIntoProvidedLib()
            throws IOException {
        try (CliTestRepository repository =
                CliTestRepository.start()) {
            addArtifact(repository, "shared-util", "");
            addArtifact(
                    repository,
                    "provided-container",
                    dependency("shared-util"));
            addArtifact(
                    repository,
                    "runtime-engine",
                    dependency("shared-util"));
            repository.addArtifact(
                    "org.springframework.boot",
                    "spring-boot-loader",
                    "4.0.6",
                    pom(
                            "org.springframework.boot",
                            "spring-boot-loader",
                            ""),
                    jarBytes(
                            "org/springframework/boot/loader/launch/WarLauncher.class"));
            Path workspace = writeWorkspace(repository);
            Path cache = tempDir.resolve("cache");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd",
                    workspace.toString(),
                    "--cache-root",
                    cache.toString());
            assertEquals(
                    0,
                    resolve.exitCode(),
                    resolve.stdout() + resolve.stderr());
            assertAggregateProvidedEntryIsDirect(workspace);

            CommandResult packaged = execute(
                    "package",
                    "--workspace",
                    "--member",
                    "apps/app",
                    "--cwd",
                    workspace.toString(),
                    "--cache-root",
                    cache.toString());
            assertEquals(
                    0,
                    packaged.exitCode(),
                    packaged.stdout() + packaged.stderr());

            Path archive =
                    workspace.resolve("apps/app/target/app-0.1.0.war");
            String sharedJar = NestedArtifactIdentity.external(
                            new PackageId(
                                    "org.example",
                                    "shared-util"),
                            "1.0.0")
                    .nestedJarName();
            try (JarFile war = new JarFile(archive.toFile())) {
                assertNotNull(war.getEntry(
                        "WEB-INF/lib/" + sharedJar));
                assertNull(war.getEntry(
                        "WEB-INF/lib-provided/"
                                + sharedJar));
                assertEquals(
                        1,
                        war.stream()
                                .filter(entry -> entry.getName()
                                        .endsWith(sharedJar))
                                .count());
                assertNotNull(war.getEntry(
                        "org/springframework/boot/loader/launch/WarLauncher.class"));
            }

            PackageEvidenceManifest evidence =
                    new PackageEvidenceManifestReader().read(
                            archive.resolveSibling(
                                    archive.getFileName()
                                            + ".zolt-package.json"));
            PackagePlanDependency runtime =
                    dependency(
                            evidence,
                            DependencyScope.RUNTIME);
            assertEquals("included", runtime.disposition());
            assertEquals(
                    "WEB-INF/lib/" + sharedJar,
                    runtime.location());
        }
    }

    private static PackagePlanDependency dependency(
            PackageEvidenceManifest evidence,
            DependencyScope scope) {
        return evidence.dependencies().stream()
                .filter(value -> value.coordinate().startsWith(
                        "org.example:shared-util:"))
                .filter(value -> value.scope() == scope)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        evidence.dependencies().toString()));
    }

    private static void assertAggregateProvidedEntryIsDirect(
            Path workspace) {
        ZoltLockfile lockfile = new ZoltLockfileReader().read(
                workspace.resolve("zolt.lock"));
        LockPackage provided = lockfile.packages().stream()
                .filter(value -> value.packageId().equals(
                        new PackageId(
                                "org.example",
                                "shared-util")))
                .filter(value -> value.scope()
                        == DependencyScope.PROVIDED)
                .findFirst()
                .orElseThrow();
        assertTrue(provided.direct());
    }

    private Path writeWorkspace(CliTestRepository repository)
            throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path app = workspace.resolve("apps/app");
        Path other = workspace.resolve("modules/other");
        Files.createDirectories(app.resolve(
                "src/main/java/com/example"));
        Files.createDirectories(other);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "provided-authority"

                [workspace.members]
                include = ["apps/app", "modules/other"]

                [repositories.test]
                url = "%s"
                """.formatted(repository.baseUri()));
        Files.writeString(app.resolve("zolt.toml"), project("app")
                + """
                main = "com.example.Main"

                [dependencies.provided]
                "org.example:provided-container" = "1.0.0"

                [dependencies.runtime]
                "org.example:runtime-engine" = "1.0.0"
                "org.springframework.boot:spring-boot-loader" = "4.0.6"

                [package]
                mode = "spring-boot-war"
                """);
        Files.writeString(
                app.resolve(
                        "src/main/java/com/example/Main.java"),
                """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        Files.writeString(
                other.resolve("zolt.toml"),
                project("other")
                        + """
                        [dependencies.provided]
                        "org.example:shared-util" = "1.0.0"
                        """);
        return workspace;
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s
                """.formatted(
                name,
                System.getProperty(
                        "java.specification.version"));
    }

    private static void addArtifact(
            CliTestRepository repository,
            String artifact,
            String dependencies) {
        repository.addArtifact(
                "org.example",
                artifact,
                "1.0.0",
                pom(
                        "org.example",
                        artifact,
                        dependencies));
    }

    private static String dependency(String artifact) {
        return """
                <dependencies>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                  </dependency>
                </dependencies>
                """.formatted(artifact);
    }

    private static String pom(
            String group,
            String artifact,
            String dependencies) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  %s
                </project>
                """.formatted(group, artifact, dependencies);
    }

    private static byte[] jarBytes(String entry)
            throws IOException {
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();
        try (JarOutputStream output =
                new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry(entry));
            output.write(0);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
