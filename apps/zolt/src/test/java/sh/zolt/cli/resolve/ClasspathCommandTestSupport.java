package sh.zolt.cli.resolve;

import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ClasspathCommandTestSupport {
    private ClasspathCommandTestSupport() {}

    static void writeProjectConfig(Path projectDir, String repositoryUrl, Map<String, String> dependencies)
            throws IOException {
        Files.createDirectories(projectDir);
        StringBuilder config = new StringBuilder("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> config.append('"')
                        .append(entry.getKey())
                        .append("\" = \"")
                        .append(entry.getValue())
                        .append("\"\n"));
        config.append("""

                [dependencies.test]
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), config.toString());
    }

    static void writeLockfile(
            Path projectDir,
            Path cacheRoot,
            String lockfileContents) throws IOException {
        Files.createDirectories(projectDir);
        write(projectDir.resolve("zolt.lock"), cacheRoot, lockfileContents);
    }

    static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
