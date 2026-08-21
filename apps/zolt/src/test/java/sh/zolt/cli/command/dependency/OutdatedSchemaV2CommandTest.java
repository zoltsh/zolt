package sh.zolt.cli.command.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.update.OutdatedEngine;
import sh.zolt.update.UpdateTargetCatalog;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class OutdatedSchemaV2CommandTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void standaloneSchemaV2ReportsCanonicalPaths() throws IOException {
        Path project = writeProject();

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--offline",
                "--cwd", project.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"manifestPath\": \"zolt.toml\""));
        assertTrue(result.stdout().contains("\"lockfilePath\": \"zolt.lock\""));
        assertTrue(result.stdout().contains(
                "\"targetId\": \"zt1_7JDO7hkQrBl5dUC14pm3rxY9MvxgOtULf2HZW3iM3j0\""));
        assertTrue(result.stdout().contains("\"updateable\": true"));
    }

    @Test
    void validSchemaV2FailureUsesTheSelectedEnvelope() {
        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--cwd", tempDir.resolve("missing").toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"status\": \"failed\""));
    }

    @Test
    void malformedRootWorkspaceFailsWithTheSelectedEnvelope() throws IOException {
        Path project = writeProject();
        Files.writeString(project.resolve("zolt.toml"), Files.readString(project.resolve("zolt.toml")) + """

                [workspace]
                name = "broken"

                [workspace.members]
                include = ["missing-member"]
                """);

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--offline",
                "--cwd", project.toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"status\": \"failed\""));
        assertTrue(result.stdout().contains("missing-member"));
    }

    @Test
    void workspaceRootPlatformIsAnAuthoritativeSchemaV2Target() throws IOException {
        Path root = tempDir.resolve("workspace-root-platform");
        Files.createDirectories(root.resolve("apps/api"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["apps/api"]

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);
        Files.writeString(root.resolve("apps/api/zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        String targetId = new UpdateTargetCatalog()
                .collect(
                        LOADER.document(root.resolve("zolt.toml")).authored(),
                        "zolt.toml",
                        "zolt.lock")
                .getFirst()
                .targetId()
                .toString();

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--offline",
                "--cwd", root.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        int rootScope = result.stdout().indexOf("\"label\": \"workspace-root\"");
        int memberScope = result.stdout().indexOf("\"label\": \"apps/api\"");
        assertTrue(rootScope >= 0 && memberScope > rootScope, result.stdout());
        assertTrue(result.stdout().contains("\"targetId\": \"" + targetId + "\""));
        assertTrue(result.stdout().contains("\"manifestPath\": \"zolt.toml\""));
        assertTrue(result.stdout().contains("\"surface\": \"platform\""));
        assertTrue(result.stdout().contains("\"identifier\": \"org.junit:junit-bom\""));
    }

    @Test
    void rootPlatformDiscoveryUsesTheRootRepositoryUniverse() throws IOException {
        Path root = writeRepositoryWorkspace(tempDir.resolve("member-repository"), List.of("private"));
        VersionDiscovery discovery = repositoryDiscovery(Map.of(
                "private", List.of("1.0.0", "1.1.0")));

        InjectedResult result = runInjected(root, discovery);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"selectedLatest\": \"1.1.0\""), result.stdout());
        assertTrue(result.stdout().contains("\"source\": \"private\""), result.stdout());
    }


    @Test
    void schemaV1PreservesDecomposedDisplayLabelsAndVersionText() throws IOException {
        String decomposed = "cafe\u0301";
        Path project = tempDir.resolve(decomposed);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:lib" = "1.0.0-%s"
                """.formatted(decomposed));

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--all",
                "--offline",
                "--cwd", project.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"label\": \"" + decomposed + "\""));
        assertTrue(result.stdout().contains("\"current\": \"1.0.0-" + decomposed + "\""));
    }

    @Test
    void schemaV1FromWorkspaceRootAcceptsDecomposedMemberPath() throws IOException {
        UnicodeWorkspace workspace = writeUnicodeWorkspace(tempDir.resolve("unicode-root"));

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--all",
                "--offline",
                "--cwd", workspace.root().toString());

        // Workspace member paths are canonicalized to Unicode NFC when they are expanded (§6.3),
        // so the display label is the composed spelling even though the directory is decomposed.
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains("\"label\": \""
                        + java.text.Normalizer.normalize(workspace.memberName(), java.text.Normalizer.Form.NFC)
                        + "\""),
                result.stdout());
    }

    @Test
    void schemaV1FromWorkspaceMemberAcceptsDecomposedMemberPath() throws IOException {
        UnicodeWorkspace workspace = writeUnicodeWorkspace(tempDir.resolve("unicode-member"));

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--all",
                "--offline",
                "--cwd", workspace.member().toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"identifier\": \"com.example:lib\""), result.stdout());
    }


    @Test
    void decomposedDependencyIdentifierIsRejectedByTheParser() throws IOException {
        String decomposed = "cafe\u0301";
        Path project = tempDir.resolve("unicode-coordinate-v2");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:%s" = "1.0.0"
                """.formatted(decomposed));

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--offline",
                "--cwd", project.toString());

        assertEquals(1, result.exitCode());
        assertTrue(
                (result.stdout() + result.stderr()).contains("Invalid dependency coordinate"),
                result.stdout() + result.stderr());
    }

    @Test
    void schemaV2RejectsDecomposedMemberPathWithActionableDiagnostic() throws IOException {
        UnicodeWorkspace workspace = writeUnicodeWorkspace(tempDir.resolve("unicode-v2"));

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--offline",
                "--cwd", workspace.root().toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"status\": \"failed\""), result.stdout());
        assertTrue(result.stdout().contains("canonical Unicode NFC path"), result.stdout());
    }

    @Test
    void unsafeCoordinateFailsBeforeMetadataDiscovery() throws IOException {
        // The final coordinate grammar rejects it at parse time, long before any repository call.
        Path project = tempDir.resolve("unsafe-coordinate");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "//metadata:probe" = "1.0.0"
                """);
        VersionDiscovery forbidden = (repositories, group, artifact, offline) -> {
            throw new AssertionError("unsafe coordinate must fail before metadata discovery");
        };

        InjectedResult result = runInjected(project, forbidden);

        assertEquals(1, result.exitCode());
        assertTrue(
                (result.stdout() + result.stderr()).contains("Invalid dependency coordinate"),
                result.stdout() + result.stderr());
    }

    @Test
    void schemaSelectionRequiresJsonAndOneSupportedVersion() throws IOException {
        Path project = writeProject();

        CommandResult text = execute(
                "outdated",
                "--schema-version", "2",
                "--cwd", project.toString());
        CommandResult unsupported = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "3",
                "--cwd", project.toString());

        assertEquals(1, text.exitCode());
        assertTrue(text.stderr().contains("--schema-version is available only with --format json"));
        assertEquals(1, unsupported.exitCode());
        assertEquals("", unsupported.stderr());
        assertTrue(unsupported.stdout().contains("\"schemaVersion\": 1"));
        assertTrue(unsupported.stdout().contains("Unsupported outdated JSON schema version `3`"));
    }

    private Path writeProject() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21


                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        return project;
    }

    private static UnicodeWorkspace writeUnicodeWorkspace(Path root) throws IOException {
        String memberName = "cafe\u0301";
        Path member = root.resolve(memberName);
        Files.createDirectories(member);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "unicode"

                [workspace.members]
                include = ["%s"]
                """.formatted(memberName));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "unicode-member"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        return new UnicodeWorkspace(root, member, memberName);
    }

    private static Path writeRepositoryWorkspace(Path root, List<String> repositoryIds) throws IOException {
        Files.createDirectories(root);
        String definitions = repositoryIds.stream()
                .map(id -> "[repositories." + id + "]\nurl = \"https://" + id + ".example.test/maven\"\n")
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "repositories"

                [workspace.members]
                include = [%s]

                [repositories]
                central = false

                %s
                [platforms]
                "com.acme:private-bom" = "1.0.0"
                """.formatted(
                        repositoryIds.stream()
                                .map(id -> "\"apps/" + id + "\"")
                                .collect(java.util.stream.Collectors.joining(", ")),
                        definitions));
        for (String repositoryId : repositoryIds) {
            Path member = root.resolve("apps").resolve(repositoryId);
            Files.createDirectories(member);
            Files.writeString(member.resolve("zolt.toml"), """
                    [project]
                    name = "%s"
                    version = "0.1.0"
                    group = "com.example"
                    java = 21
                    """.formatted(repositoryId));
        }
        return root;
    }

    private static VersionDiscovery repositoryDiscovery(Map<String, List<String>> versionsByRepository) {
        return (repositories, group, artifact, offline) -> {
            for (var repository : repositories) {
                List<String> versions = versionsByRepository.get(repository.id());
                if (versions != null) {
                    Map<String, String> sources = new java.util.LinkedHashMap<>();
                    versions.forEach(version -> sources.put(version, repository.id()));
                    return new MetadataDiscovery(true, versions, sources, List.of());
                }
            }
            return new MetadataDiscovery(false, List.of(), Map.of(), List.of("not visible"));
        };
    }

    private static InjectedResult runInjected(Path root, VersionDiscovery discovery) {
        OutdatedCommand command =
                new OutdatedCommand(new OutdatedEngine(discovery), new DependencyUpdateScopeResolver());
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        int exitCode = commandLine.execute(
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--directory", root.toString());
        return new InjectedResult(exitCode, stdout.toString(), stderr.toString());
    }

    private record InjectedResult(int exitCode, String stdout, String stderr) {
    }

    private record UnicodeWorkspace(Path root, Path member, String memberName) {
    }
}
