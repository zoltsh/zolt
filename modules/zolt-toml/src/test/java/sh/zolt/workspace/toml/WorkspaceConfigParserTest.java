package sh.zolt.workspace.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkspaceConfigParserTest {
    private final WorkspaceConfigParser parser = new WorkspaceConfigParser();

    @Test
    void parsesWorkspaceConfig() {
        WorkspaceConfig config = parser.parse("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                defaultMembers = ["apps/api"]

                [toolchain.zolt]
                version = "0.2.0"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"
                internal = "https://repo.acme.example/maven"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "4.0.6"
                "com.acme:enterprise-platform" = "2026.1.0"
                """);

        assertEquals("acme-platform", config.name());
        assertEquals(List.of("apps/api", "modules/core"), config.members());
        assertEquals(List.of("apps/api"), config.defaultMembers());
        assertEquals(Map.of(
                "central", "https://repo.maven.apache.org/maven2",
                "internal", "https://repo.acme.example/maven"), config.repositories());
        assertEquals(Map.of(
                "org.springframework.boot:spring-boot-dependencies", "4.0.6",
                "com.acme:enterprise-platform", "2026.1.0"), config.platforms());
    }

    @Test
    void parsesWorkspaceConfigFromRootZoltToml() {
        WorkspaceConfig config = parser.parseRootConfig("""
                [project]
                name = "root"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [workspace]
                name = "acme-platform"
                members = [".", "modules/core"]
                defaultMembers = ["."]

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);

        assertEquals("acme-platform", config.name());
        assertEquals(List.of(".", "modules/core"), config.members());
        assertEquals(List.of("."), config.defaultMembers());
    }

    @Test
    void retainsStructuredRootRepositoriesAndCredentialReferences() {
        WorkspaceConfig config = parser.parseRootConfig("""
                [workspace]
                name = "acme-platform"
                members = ["modules/core"]

                [repositories]
                internal = { url = "https://repo.acme.example/maven", credentials = "company" }

                [repositoryCredentials.company]
                tokenEnv = "COMPANY_REPOSITORY_TOKEN"
                """);

        assertEquals(
                "https://repo.acme.example/maven",
                config.repositories().get("internal"));
        assertEquals(
                "company",
                config.repositorySettings()
                        .get("internal")
                        .credentials()
                        .orElseThrow());
        assertEquals(
                "COMPANY_REPOSITORY_TOKEN",
                config.repositoryCredentials()
                        .get("company")
                        .tokenEnv()
                        .orElseThrow());
    }

    @Test
    void rejectsUnknownTopLevelSectionsInRootZoltToml() {
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> parser.parseRootConfig("""
                        [workspace]
                        name = "bad"
                        members = ["apps/api"]

                        [dependency]
                        typo = "true"
                        """));

        assertEquals(
                "Unknown top-level section [dependency] in zolt.toml. Remove it or check the spelling.",
                exception.getMessage());
    }

    @Test
    void rejectsUnknownTopLevelSections() {
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> parser.parse("""
                        [workspace]
                        name = "bad"
                        members = ["apps/api"]

                        [dependencies]
                        "com.example:lib" = "1.0.0"
                        """));

        assertEquals(
                "Unknown top-level section [dependencies] in zolt-workspace.toml. Remove it or check the spelling.",
                exception.getMessage());
    }

    @Test
    void rejectsMissingMembers() {
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> parser.parse("""
                        [workspace]
                        name = "bad"
                        """));

        assertEquals(
                "Missing required field [workspace].members in zolt-workspace.toml. Add an array of member paths.",
                exception.getMessage());
    }

    @Test
    void rejectsDuplicateMemberStrings() {
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> parser.parse("""
                        [workspace]
                        name = "bad"
                        members = ["apps/api", "apps/api"]
                        """));

        assertEquals(
                "Duplicate value `apps/api` in [workspace].members in zolt-workspace.toml.",
                exception.getMessage());
    }

    @Test
    void rejectsMalformedRepositoryValues() {
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> parser.parse("""
                        [workspace]
                        name = "bad"
                        members = ["apps/api"]

                        [repositories]
                        central = 42
                        """));

        assertEquals(
                "Invalid value for [repositories].central in zolt-workspace.toml. Use a non-empty URL string or { url = \"...\", credentials = \"...\" }.",
                exception.getMessage());
    }
}
