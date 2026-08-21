package sh.zolt.workspace.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

/** Shared fixtures and projections for the final-language workspace loader tests. */
final class FinalWorkspaceFixtures {
    static final String LEGACY_SHARED = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2"
            company = { url = "https://repo.example.com/maven", credentials = "company" }

            [repositoryCredentials.company]
            usernameEnv = "MAVEN_USERNAME"
            passwordEnv = "MAVEN_PASSWORD"

            [platforms]
            "com.acme:enterprise-platform" = "2026.1.0"
            """;

    private FinalWorkspaceFixtures() {
    }

    static void writeLegacyWorkspace(Path legacyRoot) throws IOException {
        write(legacyRoot, "zolt-workspace.toml", """
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/contract", "modules/core", "modules/processor", "modules/testkit"]
                defaultMembers = ["apps/api"]

                """ + LEGACY_SHARED);
        write(legacyRoot, "apps/api/zolt.toml", legacyMember("api", """
                main = "com.acme.api.Main"
                """, """
                [api.dependencies]
                "com.acme:contract" = { workspace = "modules/contract" }

                [dependencies]
                "com.acme:core" = { workspace = "modules/core", optional = true }

                [annotationProcessors]
                "com.acme:processor" = { workspace = "modules/processor" }

                [test.dependencies]
                "com.acme:testkit" = { workspace = "modules/testkit" }
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """));
        write(legacyRoot, "modules/contract/zolt.toml", legacyMember("contract", "", ""));
        write(legacyRoot, "modules/core/zolt.toml", legacyMember("core", "", """
                [api.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"

                [test.annotationProcessors]
                "com.acme:processor" = { workspace = "modules/processor" }
                """));
        write(legacyRoot, "modules/processor/zolt.toml", legacyMember("processor", "", ""));
        write(legacyRoot, "modules/testkit/zolt.toml", legacyMember("testkit", "", ""));
    }

    static void writeFinalWorkspace(Path finalRoot) throws IOException {
        write(finalRoot, "zolt.toml", """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.acme"
                version = "1.4.0"
                java = 21

                [repositories.company]
                url = "https://repo.example.com/maven"
                credentials = "company"

                [credentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "com.acme:enterprise-platform" = "2026.1.0"
                """);
        write(finalRoot, "apps/api/zolt.toml", """
                [project]
                name = "api"
                main = "com.acme.api.Main"

                [dependencies]
                "com.acme:core" = { workspace = true, optional = true }

                [dependencies.api]
                "com.acme:contract" = { workspace = true }

                [dependencies.test]
                "com.acme:testkit" = { workspace = true }
                "org.junit.jupiter:junit-jupiter" = "5.13.4"

                [dependencies.processor]
                "com.acme:processor" = { workspace = true }
                """);
        write(finalRoot, "modules/contract/zolt.toml", """
                [project]
                name = "contract"
                """);
        write(finalRoot, "modules/core/zolt.toml", """
                [project]
                name = "core"

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.17"

                [dependencies.test-processor]
                "com.acme:processor" = { workspace = true }
                """);
        write(finalRoot, "modules/processor/zolt.toml", """
                [project]
                name = "processor"
                """);
        write(finalRoot, "modules/testkit/zolt.toml", """
                [project]
                name = "testkit"
                """);
    }

    static String legacyMember(String name, String identityExtras, String sections) {
        return """
                [project]
                name = "%s"
                version = "1.4.0"
                group = "com.acme"
                java = "21"
                %s
                """.formatted(name, identityExtras)
                + LEGACY_SHARED
                + (sections.isEmpty() ? "" : "\n" + sections);
    }

    static void write(Path root, String relativePath, String content) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    static ProjectConfig member(Workspace workspace, String path) {
        return workspace.members().stream()
                .filter(member -> member.path().equals(path))
                .findFirst()
                .orElseThrow()
                .config();
    }

    static List<String> edges(Workspace workspace) {
        return workspace.edges().stream()
                .map(FinalWorkspaceFixtures::describe)
                .sorted()
                .toList();
    }

    private static String describe(WorkspaceProjectEdge edge) {
        return String.join(
                "|",
                edge.from(),
                edge.to(),
                edge.scope(),
                edge.coordinate(),
                Boolean.toString(edge.exported()),
                Boolean.toString(edge.optional()));
    }

    static List<String> directories(Workspace workspace, Path root) {
        return workspace.members().stream()
                .map(member -> root.toAbsolutePath().normalize()
                        .relativize(member.directory())
                        .toString()
                        .replace('\\', '/'))
                .toList();
    }

    static Map<String, ProjectConfig> configs(Workspace workspace) {
        Map<String, ProjectConfig> configs = new TreeMap<>();
        for (WorkspaceMember member : workspace.members()) {
            configs.put(member.path(), member.config());
        }
        return configs;
    }
}
