package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResult;
import sh.zolt.build.NativeImageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.springboot.SpringBootAotNativeInputs;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class NativeBuildServiceTest extends NativeBuildServiceTestSupport {
    @Test
    void packageAndFinalClasspathShareOneArtifactHash() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        VerifiedArtifactIndex index = new VerifiedArtifactIndex();
        NativeBuildService service = service(command -> {
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        service.buildNative(
                projectDir,
                config(Optional.of("com.example.Main")),
                cacheRoot,
                Path.of("native-image"),
                () -> {},
                index);

        assertEquals(1, index.metrics().hashes());
        assertEquals(1, index.metrics().paths());
        assertTrue(index.metrics().cacheHits() >= 1);
    }

    @Test
    void forwardsNativeImageProgressFromBuildServiceToRunner() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        List<String> progressEvents = new ArrayList<>();
        NativeBuildService service = serviceLauncher((command, progress) -> {
            progress.run();
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeBuildResult result = service.buildNative(
                projectDir,
                config(Optional.of("com.example.Main")),
                cacheRoot,
                Path.of("custom-native-image"),
                () -> progressEvents.add("Still running: Native Image"));

        assertEquals(projectDir.resolve("target/native-custom/demo-native"), result.nativeImageResult().outputBinary());
        assertEquals(List.of("Still running: Native Image"), progressEvents);
    }

    @Test
    void springBootNativeFailsBeforeInvokingNativeImage() {
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        config(Optional.of("com.example.Main"))
                                .withPackageSettings(new sh.zolt.project.PackageSettings(sh.zolt.project.PackageMode.SPRING_BOOT)),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Spring Boot native images require `[framework.springBoot.native] enabled = true`"));
        assertTrue(exception.getMessage().contains("Spring Boot JVM build, test, run, and executable packaging"));
        assertTrue(exception.getMessage().contains("explicit Zolt-owned Spring Boot AOT/native canary path"));
        assertTrue(exception.getMessage().contains("zolt package --mode spring-boot"));
        assertFalse(exception.getMessage().contains("not supported by Zolt yet"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void springBootAotNativeInputsRequireGeneratedOutputs() {
        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> new SpringBootAotNativeInputs(projectDir).classpathEntries());

        assertTrue(exception.getMessage().contains("Spring Boot native AOT output is missing"));
        assertTrue(exception.getMessage().contains("target/spring-aot/main/sources"));
    }

    @Test
    void springBootAotNativeInputsRequireReflectionMetadata() throws IOException {
        writeSpringBootAotOutput(projectDir.resolve("target/spring-aot/main"), true);
        Files.delete(projectDir.resolve(
                "target/spring-aot/main/resources/META-INF/native-image/com.example/demo/reflect-config.json"));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> new SpringBootAotNativeInputs(projectDir).classpathEntries());

        assertTrue(exception.getMessage().contains("Spring Boot AOT reflection metadata"));
        assertTrue(exception.getMessage().contains("target/spring-aot/main/resources/META-INF/native-image"));
    }

    @Test
    void springBootNativeWritesAotOutputEvidence() throws IOException {
        writeSpringBootAotOutput(projectDir.resolve(".zolt/build/spring-aot/main"), true);
        Path classes = projectDir.resolve("target/classes");
        Files.createDirectories(classes);
        Path jar = projectDir.resolve("target/demo-0.1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        PackageResult packageResult = new PackageResult(
                new BuildResult(Optional.empty(), 1, 0, classes, ""),
                jar,
                1,
                true);
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeBuildResult result = service.buildNativeImage(
                projectDir,
                springBootNativeConfig(),
                packageResult,
                List.of(),
                Path.of("native-image"));

        Path evidencePath = projectDir.resolve(".zolt/build/native/spring-aot-evidence.json");
        assertEquals(Optional.of(evidencePath), result.springBootAotEvidencePath());
        assertTrue(Files.exists(evidencePath));
        String evidence = Files.readString(evidencePath);
        assertTrue(evidence.contains("\"schema\": \"zolt.spring-aot-evidence.v1\""));
        assertTrue(evidence.contains("\"outputRoot\": \".zolt/build/spring-aot/main\""));
        assertTrue(evidence.contains("\"freshness\": \"present\""));
        assertTrue(evidence.contains("\"fingerprint\": \"sha256:"));
        assertTrue(evidence.contains("\"path\": \".zolt/build/spring-aot/main/sources/com/example/Main__BeanDefinitions.java\""));
        assertTrue(evidence.contains("\"path\": \".zolt/build/spring-aot/main/classes/com/example/Main__BeanDefinitions.class\""));
        assertTrue(evidence.contains("\"reflectionMetadata\": ["));
        assertTrue(evidence.contains("\"reachabilityMetadata\": ["));
        assertTrue(evidence.contains("reflect-config.json"));
        assertTrue(evidence.contains("reachability-metadata.json"));
        assertFalse(evidence.contains(projectDir.toString()));
        String nativeClasspath = commands.getFirst().get(commands.getFirst().indexOf("-cp") + 1);
        assertTrue(nativeClasspath.contains(projectDir.resolve(".zolt/build/spring-aot/main/classes").toString()));
        assertTrue(nativeClasspath.contains(projectDir.resolve(".zolt/build/spring-aot/main/resources").toString()));
    }

    @Test
    void explicitSpringBootNativeRequiresLockedAotToolingBeforeNativeImage() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.buildNative(
                        projectDir,
                        springBootNativeConfig(),
                        cacheRoot,
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Spring Boot AOT processing requires locked tool artifacts"));
        assertTrue(exception.getMessage().contains("tool-spring-aot"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve`"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void micronautNativeFailsBeforeInvokingNativeImage() {
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        new ZoltTomlParser().parse("""
                                [project]
                                name = "demo"
                                version = "0.1.0"
                                group = "com.example"
                                java = "21"
                                main = "com.example.Main"

                                [dependencies]
                                "io.micronaut:micronaut-http-server-netty" = "4.9.4"
                                """),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Micronaut native images are not supported"));
        assertTrue(exception.getMessage().contains("Micronaut JVM build/test flows"));
        assertTrue(exception.getMessage().contains("zolt package --mode thin"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void quarkusSupportLibrariesDoNotBlockPlainNativeBuilds() throws IOException {
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeBuildResult result = service.buildNative(
                projectDir,
                new ZoltTomlParser().parse("""
                        [project]
                        name = "demo"
                        version = "0.1.0"
                        group = "com.example"
                        java = "21"
                        main = "com.example.Main"

                        [provided.dependencies]
                        "io.quarkus:quarkus-builder" = "3.33.2"
                        "io.quarkus:quarkus-junit" = "3.33.2"
                        """),
                projectDir.resolve("cache"),
                Path.of("native-image"));

        assertEquals(projectDir.resolve("target/native/demo"), result.nativeImageResult().outputBinary());
        assertTrue(Files.exists(projectDir.resolve("target/native/demo")));
        assertEquals(1, commands.size());
    }

    @Test
    void defaultNativeOutputUsesBuildOutputRoot() throws IOException {
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeBuildResult result = service.buildNative(
                projectDir,
                new ZoltTomlParser().parse("""
                        [project]
                        name = "demo"
                        version = "0.1.0"
                        group = "com.example"
                        java = "21"
                        main = "com.example.Main"

                        [build]
                        outputRoot = ".zolt/build"
                        """),
                projectDir.resolve("cache"),
                Path.of("native-image"));

        assertEquals(projectDir.resolve(".zolt/build/native/demo"), result.nativeImageResult().outputBinary());
        assertEquals(projectDir.resolve(".zolt/build/native/native-image.log"), result.nativeImageResult().logFile());
        assertTrue(Files.exists(projectDir.resolve(".zolt/build/native/demo")));
        assertTrue(commands.getFirst().contains(projectDir.resolve(".zolt/build/native/demo").toString()));
    }

    @Test
    void explicitQuarkusNativeFailsBeforeInvokingNativeImage() {
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        new ZoltTomlParser().parse("""
                                [project]
                                name = "demo"
                                version = "0.1.0"
                                group = "com.example"
                                java = "21"
                                main = "com.example.Main"

                                [framework.quarkus]
                                enabled = true
                                """),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("Quarkus native images are not supported"));
        assertTrue(exception.getMessage().contains("Quarkus JVM build/test/package path"));
        assertTrue(exception.getMessage().contains("zolt package --mode quarkus"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void seriousNativeImageWarningsAreActionable() throws IOException {
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        NativeBuildService service = service(command -> {
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(
                    0,
                    "Warning: unsupported reflection configuration\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        config(Optional.of("com.example.Main")),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("serious warning terms"));
        assertTrue(exception.getMessage().contains(projectDir.resolve("target/native-custom/native-image.log").toString()));
        assertTrue(exception.getMessage().contains("Warning: unsupported reflection configuration"));
        assertTrue(Files.exists(projectDir.resolve("target/native-custom/native-image.log")));
    }

    private static ProjectConfig springBootNativeConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [build]
                outputRoot = ".zolt/build"

                [framework.springBoot.native]
                enabled = true

                [native]
                imageName = "demo-native"
                args = ["--no-fallback"]
                """);
    }

    private static void writeSpringBootAotOutput(Path aotRoot, boolean includeReachabilityMetadata) throws IOException {
        Path source = aotRoot.resolve("sources/com/example/Main__BeanDefinitions.java");
        Path generatedClass = aotRoot.resolve("classes/com/example/Main__BeanDefinitions.class");
        Path resource = aotRoot.resolve("resources/application.properties");
        Path reflection = aotRoot.resolve("resources/META-INF/native-image/com.example/demo/reflect-config.json");
        Files.createDirectories(source.getParent());
        Files.createDirectories(generatedClass.getParent());
        Files.createDirectories(resource.getParent());
        Files.createDirectories(reflection.getParent());
        Files.writeString(source, "package com.example; final class Main__BeanDefinitions {}\n");
        Files.writeString(generatedClass, "class");
        Files.writeString(resource, "spring.application.name=demo\n");
        Files.writeString(reflection, "[]\n");
        if (includeReachabilityMetadata) {
            Files.writeString(
                    aotRoot.resolve("resources/META-INF/native-image/com.example/demo/reachability-metadata.json"),
                    "{}\n");
        }
    }
}
