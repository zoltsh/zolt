package sh.zolt.project.init;

import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.manifest.write.ManifestCanonicalWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectInitializer {
    private final ManifestCanonicalWriter writer;

    public ProjectInitializer() {
        this(new ManifestCanonicalWriter());
    }

    public ProjectInitializer(ManifestCanonicalWriter writer) {
        this.writer = writer;
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
        int javaRelease = javaRelease(javaVersion);

        Path projectDirectory = baseDirectory.resolve(name).normalize();
        if (Files.exists(projectDirectory) && directoryHasEntries(projectDirectory)) {
            throw new ProjectInitException(
                    "Project directory " + projectDirectory + " is not empty. Choose a new name or empty the directory.");
        }

        String mainClass = group + ".Main";
        AuthoredManifest config =
                InitManifests.project(name, group, javaRelease, mainClass, includeTests);

        Path packagePath = Path.of(group.replace('.', '/'));
        Path mainSource = projectDirectory.resolve("src/main/java").resolve(packagePath).resolve("Main.java");
        Path testSource = projectDirectory.resolve("src/test/java").resolve(packagePath).resolve("MainTest.java");
        Path configFile = projectDirectory.resolve("zolt.toml");

        try {
            Files.createDirectories(mainSource.getParent());
            writeManifest(configFile, config);
            Files.writeString(mainSource, mainSource(name, group));
            if (includeTests) {
                Files.createDirectories(testSource.getParent());
                Files.writeString(testSource, testSource(name, group));
            }
            Files.writeString(projectDirectory.resolve(".gitignore"), gitignore());
        } catch (IOException exception) {
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
        return initWorkspace(baseDirectory, name, group, javaVersion, includeTests, false);
    }

    /**
     * Creates a virtual workspace root and its one application member. {@code allMembers} emits the
     * implicit-all membership table that omits {@code default} (design §6.2).
     */
    public ProjectInitResult initWorkspace(
            Path baseDirectory,
            String name,
            String group,
            String javaVersion,
            boolean includeTests,
            boolean allMembers) {
        validateProjectName(name);
        validateJavaPackage(group);
        int javaRelease = javaRelease(javaVersion);

        Path workspaceDirectory = baseDirectory.resolve(name).normalize();
        if (Files.exists(workspaceDirectory) && directoryHasEntries(workspaceDirectory)) {
            throw new ProjectInitException(
                    "Workspace directory " + workspaceDirectory + " is not empty. Choose a new name or empty the directory.");
        }

        String memberPath = "apps/" + name;
        Path projectDirectory = workspaceDirectory.resolve(memberPath).normalize();
        String mainClass = group + ".Main";
        AuthoredManifest config = InitManifests.member(name, mainClass, includeTests);
        AuthoredManifest workspaceConfig =
                InitManifests.workspaceRoot(name, group, javaRelease, memberPath, allMembers);

        Path packagePath = Path.of(group.replace('.', '/'));
        Path mainSource = projectDirectory.resolve("src/main/java").resolve(packagePath).resolve("Main.java");
        Path testSource = projectDirectory.resolve("src/test/java").resolve(packagePath).resolve("MainTest.java");
        Path rootConfigFile = workspaceDirectory.resolve("zolt.toml");
        Path memberConfigFile = projectDirectory.resolve("zolt.toml");

        try {
            Files.createDirectories(mainSource.getParent());
            writeManifest(rootConfigFile, workspaceConfig);
            writeManifest(memberConfigFile, config);
            Files.writeString(mainSource, mainSource(name, group));
            if (includeTests) {
                Files.createDirectories(testSource.getParent());
                Files.writeString(testSource, testSource(name, group));
            }
            Files.writeString(workspaceDirectory.resolve(".gitignore"), gitignore());
        } catch (IOException exception) {
            throw new ProjectInitException(
                    "Could not create Zolt workspace at " + workspaceDirectory + ". Check filesystem permissions.");
        }

        return new ProjectInitResult(workspaceDirectory, rootConfigFile, mainSource, testSource);
    }

    private void writeManifest(Path path, AuthoredManifest manifest) throws IOException {
        Files.writeString(path, writer.write(manifest));
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

    /**
     * The final language records the project Java release as an integer feature number (design
     * §7.2), so a legacy spelling such as {@code 1.8} is rejected here rather than emitted.
     */
    private static int javaRelease(String javaVersion) {
        if (javaVersion == null || javaVersion.isBlank()) {
            throw new ProjectInitException("Java version is required. Pass a non-empty --java value.");
        }
        try {
            return Integer.parseInt(javaVersion.trim());
        } catch (NumberFormatException exception) {
            throw new ProjectInitException(
                    "Java version must be a feature release number such as 21, not `" + javaVersion + "`.");
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
