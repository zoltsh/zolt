package sh.zolt.cli.dependency;

import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class RemoveCommandSectionTestSupport {
    private RemoveCommandSectionTestSupport() {}

    static void writeSectionedDependencyProject(
            Path projectDir,
            String repositoryUrl,
            Map<String, String> dependencies,
            Map<String, String> testDependencies) throws IOException {
        Files.createDirectories(projectDir);
        StringBuilder config = new StringBuilder(memberConfig("demo") + """
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [dependencies]
                """.formatted(repositoryUrl));
        appendDependencies(config, dependencies);
        config.append("\n[dependencies.test]\n");
        appendDependencies(config, testDependencies);
        config.append("""

                [dependencies.processor]
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), config.toString());
    }

    static void writeApiDependencyProject(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """

                [repositories]
                "central" = "https://repo.maven.apache.org/maven2"

                [dependencies.api]
                "com.example:contract" = "1.0.0"

                [dependencies.test]
                """);
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
