package sh.zolt.build.springboot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.compile.JavacRunnerTestSupport;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpringBootAotGenerationServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void preservesSpringGeneratedNativeMetadataWhenAddingLegacyInitializerReflection() throws IOException {
        SpringBootAotGenerationService service = new SpringBootAotGenerationService(
                javacRunnerThatWritesClassFile(),
                javaRunnerThatWritesSpringAotMetadata());

        service.generate(
                projectDir,
                springBootNativeConfig(),
                jdkStatus(),
                emptyClasspath(),
                new Classpath(List.of(projectDir.resolve("cache/spring-boot-aot.jar"))));

        Path metadataRoot = projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/com.example/demo");
        String reflection = Files.readString(metadataRoot.resolve("reflect-config.json"));
        String reachability = Files.readString(metadataRoot.resolve("reachability-metadata.json"));

        assertTrue(reflection.contains("\"name\": \"com.example.SpringGeneratedType\""));
        assertTrue(reflection.contains("\"name\": \"com.example.Main__ApplicationContextInitializer\""));
        assertTrue(reachability.contains("\"bundles\""));
        Path beanDefinitions =
                projectDir.resolve("target/spring-aot/main/classes/com/example/Main__BeanDefinitions.class");
        assertTrue(Files.exists(beanDefinitions));

        Files.delete(beanDefinitions);
        service.generate(
                projectDir,
                springBootNativeConfig(),
                jdkStatus(),
                emptyClasspath(),
                new Classpath(List.of(projectDir.resolve("cache/spring-boot-aot.jar"))));

        assertTrue(Files.exists(beanDefinitions));
    }

    private static JavaRunner javaRunnerThatWritesSpringAotMetadata() {
        return new JavaRunner(":", new JavaRunner.ProcessRunner() {
            @Override
            public JavaRunner.ProcessResult run(List<String> command, Consumer<String> outputConsumer) {
                int processor = command.indexOf("org.springframework.boot.SpringApplicationAotProcessor");
                if (processor < 0) {
                    throw new AssertionError("expected Spring AOT processor command: " + command);
                }
                Path sources = Path.of(command.get(processor + 2));
                Path resources = Path.of(command.get(processor + 3));
                try {
                    Files.createDirectories(sources.resolve("com/example"));
                    Files.writeString(
                            sources.resolve("com/example/Main__BeanDefinitions.java"),
                            "package com.example; final class Main__BeanDefinitions {}\n");
                    Files.writeString(
                            sources.resolve("com/example/Main__ApplicationContextInitializer.java"),
                            "package com.example; final class Main__ApplicationContextInitializer {}\n");
                    Path metadataRoot = resources.resolve("META-INF/native-image/com.example/demo");
                    Files.createDirectories(metadataRoot);
                    Files.writeString(
                            metadataRoot.resolve("reflect-config.json"),
                            """
                                    [
                                      {
                                        "name": "com.example.SpringGeneratedType"
                                      }
                                    ]
                                    """);
                    Files.writeString(
                            metadataRoot.resolve("reachability-metadata.json"),
                            """
                                    {
                                      "bundles": []
                                    }
                                    """);
                } catch (IOException exception) {
                    throw new AssertionError("Could not write fake Spring AOT output", exception);
                }
                return new JavaRunner.ProcessResult(0, "aot ok\n");
            }
        });
    }

    private static JavacRunner javacRunnerThatWritesClassFile() {
        return JavacRunnerTestSupport.javacRunner(":", command -> {
            int outputFlag = command.indexOf("-d");
            if (outputFlag < 0) {
                throw new AssertionError("expected javac output directory: " + command);
            }
            Path output = Path.of(command.get(outputFlag + 1));
            try {
                Files.createDirectories(output.resolve("com/example"));
                Files.writeString(output.resolve("com/example/Main__BeanDefinitions.class"), "class");
                Files.writeString(output.resolve("com/example/Main__ApplicationContextInitializer.class"), "class");
            } catch (IOException exception) {
                throw new AssertionError("Could not write fake Spring AOT class output", exception);
            }
        });
    }

    private static Classpath emptyClasspath() {
        return new Classpath(List.of());
    }

    private static JdkStatus jdkStatus() {
        return new JdkStatus(
                Optional.empty(),
                Optional.of(Path.of("java")),
                Optional.of(Path.of("javac")),
                Optional.of(Path.of("jar")),
                Optional.of("21"),
                "21");
    }

    private static ProjectConfig springBootNativeConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [framework.spring-boot]
                native = true
                """);
    }
}
