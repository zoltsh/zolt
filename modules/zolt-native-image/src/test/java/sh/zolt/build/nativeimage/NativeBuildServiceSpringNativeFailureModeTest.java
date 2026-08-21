package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.NativeImageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class NativeBuildServiceSpringNativeFailureModeTest extends NativeBuildServiceTestSupport {
    @Test
    void rejectsStaleSpringBootAotOutputBeforeNativeImage() throws IOException {
        writeSpringBootAotOutput(projectDir.resolve(".zolt/build/spring-aot/main"));
        touchTree(projectDir.resolve(".zolt/build/spring-aot/main"), FileTime.fromMillis(1_000));
        Path classes = projectDir.resolve(".zolt/build/classes/com/example/Main.class");
        Files.createDirectories(classes.getParent());
        Files.writeString(classes, "class");
        Files.setLastModifiedTime(classes, FileTime.fromMillis(2_000));
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.setLastModifiedTime(projectDir.resolve("zolt.toml"), FileTime.fromMillis(2_000));
        Path jar = projectDir.resolve("target/demo-0.1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        PackageResult packageResult = new PackageResult(
                new BuildResult(Optional.empty(), 1, 0, projectDir.resolve(".zolt/build/classes"), ""),
                jar,
                1,
                true);
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNativeImage(
                        projectDir,
                        springBootNativeConfig(),
                        packageResult,
                        List.of(),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Spring Boot native AOT output is stale"));
        assertTrue(exception.getMessage().contains("Run `zolt build`"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void rejectsUnsupportedJavaBaselineBeforePackaging() {
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        new ManifestProjectConfigLoader().load("""
                                [project]
                                name = "demo"
                                version = "0.1.0"
                                group = "com.example"
                                java = 17
                                main = "com.example.Main"

                                [framework.spring-boot]
                                native = true
                                """),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Spring Boot native support is currently proven for Java 21"));
        assertTrue(exception.getMessage().contains("[project].java = 17"));
        assertTrue(exception.getMessage().contains("[package].mode = \"spring-boot\""));
        assertTrue(exception.getMessage().contains("zolt resolve"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void rejectsUnsupportedSpringBootBaselineBeforePackaging() {
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        new ManifestProjectConfigLoader().load("""
                                [project]
                                name = "demo"
                                version = "0.1.0"
                                group = "com.example"
                                java = 21
                                main = "com.example.Main"

                                [platforms]
                                "org.springframework.boot:spring-boot-dependencies" = "4.0.6"

                                [framework.spring-boot]
                                native = true
                                """),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Spring Boot native support is currently proven for Spring Boot 3.3"));
        assertTrue(exception.getMessage().contains("Found Spring Boot 4.0.6"));
        assertTrue(exception.getMessage().contains("JVM Spring Boot path"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void rejectsSpringCloudNativeBoundaryBeforePackaging() {
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        springBootNativeBoundaryConfig("""
                                [dependencies]
                                "org.springframework.cloud:spring-cloud-starter-gateway" = "4.1.5"
                                """),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Spring Cloud native applications"));
        assertTrue(exception.getMessage().contains("not part of Zolt's proven Spring Boot native fixture family yet"));
        assertTrue(exception.getMessage().contains("Use the JVM Spring Boot path"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void rejectsExternalDatabaseNativeBoundaryBeforePackaging() {
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        springBootNativeBoundaryConfig("""
                                [dependencies.runtime]
                                "org.postgresql:postgresql" = "42.7.4"
                                """),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("external database native topologies"));
        assertTrue(exception.getMessage().contains("Spring JDBC/H2 native fixture row"));
        assertTrue(exception.getMessage().contains("JVM Spring Boot path"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    private static ProjectConfig springBootNativeConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [build.output]
                root = ".zolt/build"

                [framework.spring-boot]
                native = true

                [native]
                name = "demo-native"
                args = ["--no-fallback"]
                """);
    }

    private static ProjectConfig springBootNativeBoundaryConfig(String dependencySection) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "3.3.6"

                [framework.spring-boot]
                native = true

                %s
                """.formatted(dependencySection));
    }

    private static void writeSpringBootAotOutput(Path aotRoot) throws IOException {
        Path source = aotRoot.resolve("sources/com/example/Main__BeanDefinitions.java");
        Path generatedClass = aotRoot.resolve("classes/com/example/Main__BeanDefinitions.class");
        Path resource = aotRoot.resolve("resources/application.properties");
        Path metadata = aotRoot.resolve("resources/META-INF/native-image/com.example/demo");
        Files.createDirectories(source.getParent());
        Files.createDirectories(generatedClass.getParent());
        Files.createDirectories(resource.getParent());
        Files.createDirectories(metadata);
        Files.writeString(source, "package com.example; final class Main__BeanDefinitions {}\n");
        Files.writeString(generatedClass, "class");
        Files.writeString(resource, "spring.application.name=demo\n");
        Files.writeString(metadata.resolve("reflect-config.json"), "[]\n");
        Files.writeString(metadata.resolve("reachability-metadata.json"), "{}\n");
    }

    private static void touchTree(Path root, FileTime time) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(path, time);
            }
        }
    }
}
