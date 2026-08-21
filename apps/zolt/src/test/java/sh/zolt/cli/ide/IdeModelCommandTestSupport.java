package sh.zolt.cli.ide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class IdeModelCommandTestSupport {
    private IdeModelCommandTestSupport() {
    }

    static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        writeProjectConfig(projectDir, repositoryUrl, currentJavaMajorVersion(), Map.of(), Map.of());
    }

    static void writeProjectConfig(
            Path projectDir,
            String repositoryUrl,
            Map<String, String> dependencies,
            Map<String, String> testDependencies) throws IOException {
        writeProjectConfig(projectDir, repositoryUrl, currentJavaMajorVersion(), dependencies, testDependencies);
    }

    static void writeProjectConfig(
            Path projectDir,
            String repositoryUrl,
            String javaVersion,
            Map<String, String> dependencies,
            Map<String, String> testDependencies) throws IOException {
        Files.createDirectories(projectDir);
        StringBuilder config = new StringBuilder("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [dependencies]
                """.formatted(javaVersion, repositoryUrl));
        appendDependencies(config, dependencies);
        config.append("\n[dependencies.test]\n");
        appendDependencies(config, testDependencies);
        config.append("""

                """);
        Files.writeString(projectDir.resolve("zolt.toml"), config.toString());
    }

    static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    static String jsonPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void appendDependencies(StringBuilder config, Map<String, String> dependencies) {
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> config.append('"')
                        .append(entry.getKey())
                        .append("\" = \"")
                        .append(entry.getValue())
                        .append("\"\n"));
    }
}
