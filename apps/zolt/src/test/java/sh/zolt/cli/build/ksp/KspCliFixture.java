package sh.zolt.cli.build.ksp;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Publishes a real KSP2 engine closure and service-loaded processor to a hermetic repository. */
final class KspCliFixture {
    static final String KSP_VERSION = "2.2.0-2.0.2";
    static final String PROCESSOR_GROUP = "com.example";
    static final String PROCESSOR_ARTIFACT = "ksp-cli-processor";
    static final String PROCESSOR_VERSION = "1.0.0";
    private static final String COROUTINES_VERSION = "1.8.0";
    private static final String PROVIDER_SERVICE =
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider";

    private static final Artifact ENGINE = artifact(
            "com.google.devtools.ksp", "symbol-processing-aa", KSP_VERSION,
            "com.google.devtools.ksp.cmdline.KSPJvmMain");
    private static final Artifact API = artifact(
            "com.google.devtools.ksp", "symbol-processing-api", KSP_VERSION,
            "com.google.devtools.ksp.processing.SymbolProcessorProvider");
    private static final Artifact COMMON = artifact(
            "com.google.devtools.ksp", "symbol-processing-common-deps", KSP_VERSION,
            "com.google.devtools.ksp.processing.KSPJvmConfig");
    private static final Artifact STDLIB = artifact(
            "org.jetbrains.kotlin", "kotlin-stdlib", KotlinCompilerCliFixture.KOTLIN_VERSION, "");
    private static final Artifact COROUTINES = artifact(
            "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", COROUTINES_VERSION, "");

    private KspCliFixture() {
    }

    static void publish(CliTestRepository repository, Path workDirectory) throws IOException {
        publish(repository, ENGINE, List.of(API, COMMON, STDLIB, COROUTINES));
        publish(repository, API, List.of(STDLIB));
        publish(repository, COMMON, List.of(STDLIB));
        repository.addArtifact(
                PROCESSOR_GROUP,
                PROCESSOR_ARTIFACT,
                PROCESSOR_VERSION,
                pom(artifact(PROCESSOR_GROUP, PROCESSOR_ARTIFACT, PROCESSOR_VERSION, ""), List.of()),
                Files.readAllBytes(processorJar(workDirectory)));
    }

    private static void publish(
            CliTestRepository repository,
            Artifact artifact,
            List<Artifact> dependencies) throws IOException {
        repository.addArtifact(
                artifact.group(),
                artifact.name(),
                artifact.version(),
                pom(artifact, dependencies),
                Files.readAllBytes(markerJar(artifact.markerClass())));
    }

    private static String pom(Artifact artifact, List<Artifact> dependencies) {
        String dependencyXml = dependencies.stream()
                .map(dependency -> """
                        <dependency>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </dependency>
                        """.formatted(
                        dependency.group(),
                        dependency.name(),
                        dependency.version()))
                .reduce("", String::concat);
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(
                artifact.group(),
                artifact.name(),
                artifact.version(),
                dependencyXml);
    }

    private static Path markerJar(String markerClass) {
        try {
            Class<?> marker = Class.forName(markerClass, false, KspCliFixture.class.getClassLoader());
            Path location = Path.of(marker.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(location) || !location.getFileName().toString().endsWith(".jar")) {
                throw new IllegalStateException(
                        "KSP integration marker " + markerClass + " is not loaded from a JAR: " + location);
            }
            return location;
        } catch (ClassNotFoundException | URISyntaxException exception) {
            throw new IllegalStateException(
                    "KSP integration marker is unavailable: " + markerClass,
                    exception);
        }
    }

    private static Path processorJar(Path workDirectory) throws IOException {
        Path jarPath = workDirectory.resolve(PROCESSOR_ARTIFACT + "-" + PROCESSOR_VERSION + ".jar");
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            addClass(jar, KspCliFixture.class);
            addClass(jar, Provider.class);
            addClass(jar, Processor.class);
            add(jar, PROVIDER_SERVICE, Provider.class.getName() + "\n");
        }
        return jarPath;
    }

    private static void addClass(JarOutputStream jar, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing compiled KSP CLI fixture class " + resource);
            }
            JarEntry entry = new JarEntry(resource);
            entry.setTime(0L);
            jar.putNextEntry(entry);
            input.transferTo(jar);
            jar.closeEntry();
        }
    }

    private static void add(JarOutputStream jar, String name, String content) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    public static final class Provider implements SymbolProcessorProvider {
        @Override
        public SymbolProcessor create(SymbolProcessorEnvironment environment) {
            String message = environment.getOptions().get("fixture.message");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("KSP CLI fixture message is required.");
            }
            return new Processor(environment.getCodeGenerator(), message);
        }
    }

    private static final class Processor implements SymbolProcessor {
        private final CodeGenerator generator;
        private final String message;
        private boolean generated;

        private Processor(CodeGenerator generator, String message) {
            this.generator = generator;
            this.message = message;
        }

        @Override
        public List<KSAnnotated> process(Resolver resolver) {
            if (generated) {
                return List.of();
            }
            generated = true;
            Dependencies dependencies = new Dependencies(false);
            write(generator.createNewFile(
                    dependencies, "com.example", "GeneratedKspMessage", "kt"), """
                    package com.example

                    object GeneratedKspMessage {
                        @JvmStatic
                        fun value(): String = "%s"
                    }
                    """.formatted(message));
            write(generator.createNewFile(
                    dependencies, "com.example", "GeneratedJavaMessage", "java"), """
                    package com.example;

                    public final class GeneratedJavaMessage {
                        private GeneratedJavaMessage() {}

                        public static String value() {
                            return GeneratedKspMessage.value();
                        }
                    }
                    """);
            write(generator.createNewFileByPath(
                    dependencies, "META-INF/ksp-cli", "txt"), message + "-resource\n");
            return List.of();
        }

        private static void write(OutputStream output, String content) {
            try (output) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static Artifact artifact(
            String group,
            String name,
            String version,
            String markerClass) {
        return new Artifact(group, name, version, markerClass);
    }

    private record Artifact(
            String group,
            String name,
            String version,
            String markerClass) {
    }
}
