package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.OutdatedEngine;
import sh.zolt.update.UpdateEngine;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

final class WorkspaceEffectiveRepositoryUpdateTest {
    private static final String TOKEN_ENV = "ZOLT_TEST_PRIVATE_TOKEN";

    @TempDir
    private Path tempDir;

    @ParameterizedTest(name = "outdated schema {0} from {1}")
    @MethodSource("schemaVersions")
    void memberDependencyDiscoveryUsesRootOnlyRepository(String schemaVersion, String invocation) throws IOException {
        Path root = writeWorkspace(
                tempDir.resolve("schema-" + schemaVersion + "-" + invocation),
                rootRepository(false),
                dependency());
        Path start = invocation.equals("root") ? root : root.resolve("apps/api");

        Result result = outdated(start, listing(false), schemaVersion);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"selectedLatest\": \"1.1.0\""), result.stdout());
        assertTrue(result.stdout().contains("\"source\": \"private\""), result.stdout());
    }

    @Test
    void memberDependencyDiscoveryUsesRootOnlyAuthenticatedRepository() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("authenticated"), rootRepository(true), dependency());

        Result result = outdated(root, listing(true), "2");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"selectedLatest\": \"1.1.0\""), result.stdout());
    }

    @Test
    void memberAliasDiscoveryUsesRootOnlyRepository() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("alias"), rootRepository(false), """
                [versions]
                private = "1.0.0"

                [dependencies]
                "com.example:private-lib" = { versionRef = "private" }
                """);

        Result result = outdated(root, listing(false), "2");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"surface\": \"versionAlias\""), result.stdout());
        assertTrue(result.stdout().contains("\"selectedLatest\": \"1.1.0\""), result.stdout());
    }

    @Test
    void policyUpdateUsesRootOnlyRepositoryForMemberDependency() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("policy"), rootRepository(false), dependency());
        Path memberManifest = root.resolve("apps/api/zolt.toml");

        Result result = policyUpdate(memberManifest.getParent(), listing(false), () -> {});

        assertEquals(0, result.exitCode(), () -> result.stdout() + result.stderr());
        assertTrue(Files.readString(memberManifest).contains("\"com.example:private-lib\" = \"1.1.0\""));
    }

    @Test
    void policyUpdateAcceptsDecomposedWorkspaceMemberPath() throws IOException {
        String memberName = "cafe\u0301";
        Path root = tempDir.resolve("unicode-policy");
        Path member = root.resolve(memberName);
        Files.createDirectories(member);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "unicode-policy"
                members = ["%s"]

                %s
                """.formatted(memberName, rootRepository(false)));
        Path memberManifest = member.resolve("zolt.toml");
        Files.writeString(memberManifest, """
                [project]
                name = "unicode-member"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                """.formatted(dependency()));

        Result result = policyUpdate(member, listing(false), () -> {});

        assertEquals(0, result.exitCode(), () -> result.stdout() + result.stderr());
        assertTrue(Files.readString(memberManifest).contains("\"com.example:private-lib\" = \"1.1.0\""));
    }

    @Test
    void policyUpdateRejectsRootRepositoryChangeAfterPlanning() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("policy-race"), rootRepository(false), dependency());
        Path rootManifest = root.resolve("zolt.toml");
        Path memberManifest = root.resolve("apps/api/zolt.toml");
        String memberOriginal = Files.readString(memberManifest);
        String rootConcurrent = Files.readString(rootManifest)
                .replace("https://root.example.test/maven", "https://changed.example.test/maven");

        Result result = policyUpdate(
                memberManifest.getParent(),
                listing(false),
                () -> writeUnchecked(rootManifest, rootConcurrent));

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Workspace policy changed"), result.stderr());
        assertEquals(rootConcurrent, Files.readString(rootManifest));
        assertEquals(memberOriginal, Files.readString(memberManifest));
    }

    @ParameterizedTest(name = "conflict {0}")
    @MethodSource("conflictingCommands")
    void conflictingRootAndMemberRepositoriesFailClosed(String command) throws IOException {
        Path root = writeWorkspace(
                tempDir.resolve("conflict-" + command),
                rootRepository(false),
                """
                [repositories]
                private = "https://member.example.test/maven"

                %s
                """.formatted(dependency()));

        Result result = switch (command) {
            case "outdated-v1" -> outdated(root, listing(false), "1");
            case "outdated-v2" -> outdated(root, listing(false), "2");
            default -> policyUpdate(root.resolve("apps/api"), listing(false), () -> {});
        };

        assertEquals(1, result.exitCode());
        assertTrue((result.stdout() + result.stderr()).contains("Workspace repository `private`"));
    }

    private static Stream<Arguments> schemaVersions() {
        return Stream.of(
                Arguments.of("1", "root"),
                Arguments.of("1", "member"),
                Arguments.of("2", "root"),
                Arguments.of("2", "member"));
    }

    private static Stream<Arguments> conflictingCommands() {
        return Stream.of(
                Arguments.of("outdated-v1"),
                Arguments.of("outdated-v2"),
                Arguments.of("policy"));
    }

    private static String rootRepository(boolean authenticated) {
        if (!authenticated) {
            return """
                    [repositories]
                    private = "https://root.example.test/maven"
                    """;
        }
        return """
                [repositories.private]
                url = "https://root.example.test/maven"
                credentials = "company"

                [repositoryCredentials.company]
                tokenEnv = "%s"
                """.formatted(TOKEN_ENV);
    }

    private static String dependency() {
        return """
                [dependencies]
                "com.example:private-lib" = "1.0.0"
                """;
    }

    private static Path writeWorkspace(Path root, String rootPolicy, String memberPolicy) throws IOException {
        Files.createDirectories(root.resolve("apps/api"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "repositories"
                members = ["apps/api"]

                %s
                """.formatted(rootPolicy));
        Files.writeString(root.resolve("apps/api/zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                """.formatted(memberPolicy));
        return root;
    }

    private static VersionDiscovery listing(boolean requireAuthentication) {
        return (repositories, group, artifact, offline) -> {
            boolean visible = repositories.stream().anyMatch(repository -> repository.id().equals("private")
                    && (!requireAuthentication || repository.authentication().isPresent()));
            if (!visible || !group.equals("com.example") || !artifact.equals("private-lib")) {
                return new MetadataDiscovery(false, List.of(), Map.of(), List.of("not visible"));
            }
            return new MetadataDiscovery(
                    true,
                    List.of("1.0.0", "1.1.0"),
                    Map.of("1.0.0", "private", "1.1.0", "private"),
                    List.of());
        };
    }

    private static Result outdated(Path root, VersionDiscovery discovery, String schemaVersion) {
        RepositoryAccessPlanner planner = new RepositoryAccessPlanner(name -> TOKEN_ENV.equals(name) ? "token" : null);
        OutdatedCommand command =
                new OutdatedCommand(new OutdatedEngine(discovery, planner), new DependencyUpdateScopeResolver());
        List<String> arguments = new ArrayList<>(List.of("--format", "json", "--directory", root.toString()));
        if (schemaVersion.equals("2")) {
            arguments.addAll(List.of("--schema-version", "2"));
        }
        return execute(command, arguments);
    }

    private static Result policyUpdate(Path member, VersionDiscovery discovery, Runnable beforeExecution) {
        RepositoryAccessPlanner planner = new RepositoryAccessPlanner(name -> TOKEN_ENV.equals(name) ? "token" : null);
        UpdateCommand command = new UpdateCommand(
                new ZoltTomlParser(),
                new ZoltTomlWriter(),
                null,
                new UpdateEngine(discovery, planner),
                beforeExecution);
        return execute(command, List.of("--format", "json", "--no-resolve", "--directory", member.toString()));
    }

    private static void writeUnchecked(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static Result execute(Object command, List<String> arguments) {
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        int exitCode = commandLine.execute(arguments.toArray(String[]::new));
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
