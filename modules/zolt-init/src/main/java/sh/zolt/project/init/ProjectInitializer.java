package sh.zolt.project.init;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigWriteException;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProjectInitializer {
    private static final String DEFAULT_TEST_FRAMEWORK = "org.junit.jupiter:junit-jupiter";
    private static final String DEFAULT_TEST_FRAMEWORK_VERSION = "5.14.4";

    private final ProjectConfigWriter writer;
    private final WorkspaceConfigWriter workspaceWriter;

    public ProjectInitializer(ProjectConfigWriter writer, WorkspaceConfigWriter workspaceWriter) {
        this.writer = writer;
        this.workspaceWriter = workspaceWriter;
    }

    public ProjectInitResult init(Path baseDirectory, String name, String group, String javaVersion) {
        return init(baseDirectory, name, group, javaVersion, true);
    }

    public ProjectInitResult init(
            Path baseDirectory,
            String name,
            String group,
            String javaVersion,
            boolean includeTests) {
        validateProjectName(name);
        validateJavaPackage(group);
        validateJavaVersion(javaVersion);

        Path projectDirectory = baseDirectory.resolve(name).normalize();
        if (Files.exists(projectDirectory) && directoryHasEntries(projectDirectory)) {
            throw new ProjectInitException(
                    "Project directory " + projectDirectory + " is not empty. Choose a new name or empty the directory.");
        }

        String mainClass = group + ".Main";
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", group, javaVersion, Optional.of(mainClass)),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                testDependencies(includeTests),
                BuildSettings.defaults());

        Path packagePath = Path.of(group.replace('.', '/'));
        Path mainSource = projectDirectory.resolve("src/main/java").resolve(packagePath).resolve("Main.java");
        Path testSource = projectDirectory.resolve("src/test/java").resolve(packagePath).resolve("MainTest.java");
        Path configFile = projectDirectory.resolve("zolt.toml");

        try {
            Files.createDirectories(mainSource.getParent());
            writer.write(configFile, config);
            Files.writeString(mainSource, mainSource(name, group));
            if (includeTests) {
                Files.createDirectories(testSource.getParent());
                Files.writeString(testSource, testSource(name, group));
            }
            Files.writeString(projectDirectory.resolve(".gitignore"), gitignore());
        } catch (IOException | ProjectConfigWriteException exception) {
            throw new ProjectInitException(
                    "Could not create Zolt project at " + projectDirectory + ". Check filesystem permissions.");
        }

        return new ProjectInitResult(projectDirectory, configFile, mainSource, testSource);
    }

    public ProjectInitResult initWorkspace(Path baseDirectory, String name, String group, String javaVersion) {
        return initWorkspace(baseDirectory, name, group, javaVersion, true);
    }

    public ProjectInitResult initWorkspace(
            Path baseDirectory,
            String name,
            String group,
            String javaVersion,
            boolean includeTests) {
        validateProjectName(name);
        validateJavaPackage(group);
        validateJavaVersion(javaVersion);

        Path workspaceDirectory = baseDirectory.resolve(name).normalize();
        if (Files.exists(workspaceDirectory) && directoryHasEntries(workspaceDirectory)) {
            throw new ProjectInitException(
                    "Workspace directory " + workspaceDirectory + " is not empty. Choose a new name or empty the directory.");
        }

        String memberPath = "apps/" + name;
        Path projectDirectory = workspaceDirectory.resolve(memberPath).normalize();
        String mainClass = group + ".Main";
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", group, javaVersion, Optional.of(mainClass)),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                testDependencies(includeTests),
                BuildSettings.defaults());
        WorkspaceInitConfig workspaceConfig = new WorkspaceInitConfig(
                name,
                List.of(memberPath),
                List.of(memberPath),
                ProjectConfig.defaultRepositories(),
                Map.of());

        Path packagePath = Path.of(group.replace('.', '/'));
        Path mainSource = projectDirectory.resolve("src/main/java").resolve(packagePath).resolve("Main.java");
        Path testSource = projectDirectory.resolve("src/test/java").resolve(packagePath).resolve("MainTest.java");
        Path rootConfigFile = workspaceDirectory.resolve("zolt.toml");
        Path memberConfigFile = projectDirectory.resolve("zolt.toml");

        try {
            Files.createDirectories(mainSource.getParent());
            workspaceWriter.write(rootConfigFile, workspaceConfig);
            writer.write(memberConfigFile, config);
            Files.writeString(mainSource, mainSource(name, group));
            if (includeTests) {
                Files.createDirectories(testSource.getParent());
                Files.writeString(testSource, testSource(name, group));
            }
            Files.writeString(workspaceDirectory.resolve(".gitignore"), gitignore());
        } catch (IOException | ProjectConfigWriteException exception) {
            throw new ProjectInitException(
                    "Could not create Zolt workspace at " + workspaceDirectory + ". Check filesystem permissions.");
        }

        return new ProjectInitResult(workspaceDirectory, rootConfigFile, mainSource, testSource);
    }

    private static boolean directoryHasEntries(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        } catch (IOException exception) {
            throw new ProjectInitException(
                    "Could not inspect project directory " + directory + ". Check filesystem permissions.");
        }
    }

    private static String mainSource(String projectName, String group) {
        return """
                package %s;

                public final class Main {
                    private Main() {
                    }

                    public static void main(String[] args) {
                        System.out.println(greeting());
                    }

                    static String greeting() {
                        return "Hello from %s!";
                    }
                }
                """.formatted(group, escapeJavaString(projectName));
    }

    private static String testSource(String projectName, String group) {
        return """
                package %s;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                final class MainTest {
                    @Test
                    void greets() {
                        assertEquals("Hello from %s!", Main.greeting());
                    }
                }
                """.formatted(group, escapeJavaString(projectName));
    }

    private static Map<String, String> testDependencies(boolean includeTests) {
        return includeTests
                ? Map.of(DEFAULT_TEST_FRAMEWORK, DEFAULT_TEST_FRAMEWORK_VERSION)
                : Map.of();
    }

    private static String gitignore() {
        return """
                target/
                build/
                out/

                .DS_Store
                """;
    }

    private static void validateProjectName(String name) {
        if (name == null || name.isBlank()) {
            throw new ProjectInitException("Project name is required. Try `zolt init hello`.");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new ProjectInitException("Project name must be a directory name, not a path.");
        }
    }

    private static void validateJavaPackage(String group) {
        if (group == null || group.isBlank()) {
            throw new ProjectInitException("Project group is required. Pass a Java package-like --group value.");
        }

        for (String part : group.split("\\.")) {
            if (!isJavaIdentifier(part)) {
                throw new ProjectInitException(
                        "Project group must be a valid Java package, for example `com.example`.");
            }
        }
    }

    private static void validateJavaVersion(String javaVersion) {
        if (javaVersion == null || javaVersion.isBlank()) {
            throw new ProjectInitException("Java version is required. Pass a non-empty --java value.");
        }
    }

    private static boolean isJavaIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String escapeJavaString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
