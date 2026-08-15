package sh.zolt.build.springboot;

import sh.zolt.build.BuildException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.JavacException;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class SpringBootAotGenerationService {
    private static final String SPRING_APPLICATION_AOT_PROCESSOR = "org.springframework.boot.SpringApplicationAotProcessor";

    private final JavacRunner javacRunner;
    private final JavaRunner javaRunner;

    public SpringBootAotGenerationService(JavacRunner javacRunner) {
        this(javacRunner, new JavaRunner());
    }

    public SpringBootAotGenerationService(JavacRunner javacRunner, JavaRunner javaRunner) {
        this.javacRunner = javacRunner;
        this.javaRunner = javaRunner;
    }

    public void generate(
            Path projectDirectory,
            ProjectConfig config,
            JdkStatus jdkStatus,
            Classpath applicationRuntimeClasspath,
            Classpath springBootAotClasspath) {
        if (!config.frameworkSettings().springBoot().nativeEnabled()) {
            return;
        }
        if (springBootAotClasspath.entries().isEmpty()) {
            throw new BuildException(
                    "Spring Boot AOT processing requires locked tool artifacts in scope `tool-spring-aot`, "
                            + "but zolt.lock does not contain them. Run `zolt resolve` without --offline to seed Spring Boot AOT tooling, then retry.");
        }
        String mainClass = config.project().main().orElseThrow(() -> new BuildException(
                "Spring Boot AOT processing requires [project].main. Add the application main class to zolt.toml."));
        Path root = projectDirectory.toAbsolutePath().normalize();
        Path outputRoot = outputRoot(root, config);
        Path sourcesRoot = outputRoot.resolve("sources");
        Path resourcesRoot = outputRoot.resolve("resources");
        Path classesRoot = outputRoot.resolve("classes");
        deleteOutput(outputRoot);
        FilesCreateDirectories.create(sourcesRoot);
        FilesCreateDirectories.create(resourcesRoot);
        FilesCreateDirectories.create(classesRoot);
        Classpath processorClasspath = processorClasspath(
                projectDirectory,
                config,
                applicationRuntimeClasspath,
                springBootAotClasspath);
        runProcessor(
                jdkStatus,
                processorClasspath,
                mainClass,
                sourcesRoot,
                resourcesRoot,
                classesRoot,
                config);
        writeLegacyInitializerReflectionConfig(resourcesRoot, config, mainClass);
        List<Path> generatedSources = javaSources(sourcesRoot);
        if (generatedSources.isEmpty()) {
            throw new BuildException(
                    "Spring Boot AOT processing completed without generated Java sources under "
                            + sourcesRoot
                            + ". Check the Spring Boot AOT output and retry.");
        }
        javacRunner.compile(
                jdkStatus.javac().orElseThrow(() -> new JavacException(
                        "Could not compile Spring Boot AOT outputs because javac is missing.")),
                generatedSources,
                withGeneratedClasses(processorClasspath, classesRoot),
                classesRoot);
    }

    private void runProcessor(
            JdkStatus jdkStatus,
            Classpath processorClasspath,
            String mainClass,
            Path sourcesRoot,
            Path resourcesRoot,
            Path classesRoot,
            ProjectConfig config) {
        try {
            javaRunner.run(
                    jdkStatus.java().orElseThrow(() -> new JavaRunException(
                            "Could not run Spring Boot AOT processing because java is missing.")),
                    processorClasspath,
                    SPRING_APPLICATION_AOT_PROCESSOR,
                    List.of(
                            mainClass,
                            sourcesRoot.toString(),
                            resourcesRoot.toString(),
                            classesRoot.toString(),
                            config.project().group(),
                            config.project().name()));
        } catch (JavaRunException exception) {
            throw new BuildException(
                    "Spring Boot AOT processing failed. Zolt runs Spring Boot AOT as a typed build step using resolved "
                            + "`tool-spring-aot` artifacts; review the output, fix unsupported Spring native inputs, and retry.\n"
                            + exception.getMessage(),
                    exception);
        }
    }

    private static Classpath processorClasspath(
            Path projectDirectory,
            ProjectConfig config,
            Classpath applicationRuntimeClasspath,
            Classpath springBootAotClasspath) {
        List<Path> entries = new ArrayList<>();
        entries.add(projectDirectory.resolve(config.build().output()).normalize());
        entries.addAll(applicationRuntimeClasspath.entries());
        entries.addAll(springBootAotClasspath.entries());
        return new Classpath(entries);
    }

    private static Classpath withGeneratedClasses(Classpath classpath, Path classesRoot) {
        List<Path> entries = new ArrayList<>();
        entries.add(classesRoot);
        entries.addAll(classpath.entries());
        return new Classpath(entries);
    }

    private static List<Path> javaSources(Path sourcesRoot) {
        if (!Files.isDirectory(sourcesRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sourcesRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not inspect Spring Boot AOT generated sources under "
                            + sourcesRoot
                            + ". Check filesystem permissions and retry.",
                    exception);
        }
    }

    private static void writeLegacyInitializerReflectionConfig(
            Path resourcesRoot,
            ProjectConfig config,
            String mainClass) {
        String initializerClass = mainClass + "__ApplicationContextInitializer";
        Path path = resourcesRoot.resolve("META-INF/native-image")
                .resolve(safePathComponent(config.project().group()))
                .resolve(safePathComponent(config.project().name()))
                .resolve("reflect-config.json");
        try {
            Files.createDirectories(path.getParent());
            String initializerEntry = """
                  {
                    "name": "%s",
                    "allDeclaredConstructors": true
                  }
                """.formatted(jsonString(initializerClass));
            if (!Files.exists(path)) {
                Files.writeString(path, "[\n" + initializerEntry + "]\n");
                return;
            }
            String existing = Files.readString(path);
            if (existing.contains("\"name\": \"" + jsonString(initializerClass) + "\"")) {
                return;
            }
            int closingArray = existing.lastIndexOf(']');
            if (closingArray < 0) {
                throw new BuildException(
                        "Could not merge Spring Boot legacy Native Image reflection config at "
                                + path
                                + " because it is not a JSON array.");
            }
            String prefix = existing.substring(0, closingArray).stripTrailing();
            String suffix = existing.substring(closingArray);
            String separator = prefix.endsWith("[") ? "\n" : ",\n";
            Files.writeString(path, prefix + separator + initializerEntry + suffix);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write Spring Boot legacy Native Image reflection config at "
                            + path
                            + ". Check filesystem permissions and retry `zolt build`.",
                    exception);
        }
    }

    private static String safePathComponent(String value) {
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '.'
                    || current == '_'
                    || current == '-') {
                safe.append(current);
            } else {
                safe.append('_');
            }
        }
        return safe.isEmpty() ? "app" : safe.toString();
    }

    private static String jsonString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Path outputRoot(Path root, ProjectConfig config) {
        try {
            return ProjectPaths.output(
                    root,
                    "Spring Boot AOT output",
                    config.build().outputRoot() + "/spring-aot/main");
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static void deleteOutput(Path outputRoot) {
        if (!Files.exists(outputRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(outputRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not clean Spring Boot AOT output "
                            + outputRoot
                            + ". Check filesystem permissions and retry `zolt build`.",
                    exception);
        }
    }

    private static final class FilesCreateDirectories {
        private FilesCreateDirectories() {
        }

        static void create(Path path) {
            try {
                Files.createDirectories(path);
            } catch (IOException exception) {
                throw new BuildException(
                        "Could not create Spring Boot AOT output directory "
                                + path
                                + ". Check filesystem permissions and retry `zolt build`.",
                        exception);
            }
        }
    }
}
