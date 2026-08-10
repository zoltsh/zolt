package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.OutdatedSurface;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateTarget;
import sh.zolt.update.UpdateTargetCatalog;
import sh.zolt.update.UpdateTargetId;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class DependencyExactUpdateCommandTest {
    @TempDir
    private Path tempDir;

    private final ZoltTomlParser parser = new ZoltTomlParser();
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();

    @Test
    void exactJsonAppliesOneTargetWithoutMetadataDiscovery() throws IOException {
        Path project = writeProject(tempDir.resolve("json"), """
                [dependencies]
                "com.example:lib" = "1.0.0"

                [workspace]
                name = "retained-source-domain"
                members = []
                """);
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");

        Result result = run(project, () -> {}, exactArgs(target, "1.1.0", "--format", "json", "--schema-version", "2", "--no-resolve"));

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"targetId\": \"" + target.targetId() + "\""));
        assertTrue(result.stdout().contains("\"changed\": true"));
        assertTrue(result.stdout().contains("\"applied\": true"));
        assertTrue(result.stdout().contains("\"resolved\": false"));
        assertTrue(result.stdout().contains("\"changedFiles\": [\n    \"zolt.toml\""));
        assertTrue(Files.readString(project.resolve("zolt.toml")).contains("\"com.example:lib\" = \"1.1.0\""));
        assertFalse(Files.exists(project.resolve("zolt.lock")));
    }

    @Test
    void exactTextReportsTheAppliedVersion() throws IOException {
        Path project = writeProject(tempDir.resolve("text"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");

        Result result = run(project, () -> {}, exactArgs(target, "2.0.0", "--no-resolve"));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Updated:"));
        assertTrue(result.stdout().contains("1.0.0 -> 2.0.0  (major)"));
    }

    @Test
    void validatesExactModeOptionCombinationsInsideExecution() throws IOException {
        Path project = writeProject(tempDir.resolve("options"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        String id = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]")
                .targetId().toString();
        List<List<String>> invalid = List.of(
                List.of("--target-id", id),
                List.of("--to", "1.1.0"),
                List.of("--target-id", id, "--to", "1.1.0", "com.example:other"),
                List.of("--target-id", id, "--to", "1.1.0", "--patch"),
                List.of("--target-id", id, "--to", "1.1.0", "--minor"),
                List.of("--target-id", id, "--to", "1.1.0", "--major"),
                List.of("--target-id", id, "--to", "1.1.0", "--latest"),
                List.of("--target-id", id, "--to", "1.1.0", "--offline"),
                List.of("--target-id", id, "--to", "1.1.0", "--format", "json"),
                List.of("--format", "json", "--schema-version", "2"));

        for (List<String> arguments : invalid) {
            Result result = run(project, () -> {}, arguments.toArray(String[]::new));
            assertEquals(1, result.exitCode(), String.join(" ", arguments));
        }
        assertTrue(Files.readString(project.resolve("zolt.toml")).contains("\"com.example:lib\" = \"1.0.0\""));
    }

    @Test
    void validMachineValidationFailureUsesSchemaV2Envelope() throws IOException {
        Path project = writeProject(tempDir.resolve("envelope"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        String id = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]")
                .targetId().toString();

        Result result = run(
                project,
                () -> {},
                "--target-id", id,
                "--format", "json",
                "--schema-version", "2");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"status\": \"failed\""));
        assertTrue(result.stdout().contains("--target-id` and `--to"));
        assertTrue(result.stdout().contains("\"nextStep\": \"Pass both options"));
    }

    @Test
    void rejectsMalformedUnknownAndNonUpdateableTargets() throws IOException {
        Path project = writeProject(tempDir.resolve("failures"), """
                [dependencies]
                "com.example:lib" = "1.0.0"

                [generated.openapiTool]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/api.yaml"
                output = "target/generated/sources/openapi/api"
                generator = "spring"
                """);
        UpdateTarget generated = target(project, "zolt.toml", OutdatedSurface.OPENAPI_TOOL, "[generated.openapiTool]");
        UpdateTargetId unknown = UpdateTargetId.create(
                "zolt.toml", OutdatedSurface.DEPENDENCY, "[test.dependencies]", "com.example:lib");

        Result malformed = run(project, () -> {}, exactArgs("not-a-target", "1.1.0", "--no-resolve"));
        Result stale = run(project, () -> {}, exactArgs(unknown.toString(), "1.1.0", "--no-resolve"));
        Result blocked = run(project, () -> {}, exactArgs(generated, "8.0.0", "--no-resolve"));

        assertEquals(1, malformed.exitCode());
        assertTrue(malformed.stderr().contains("Invalid Zolt update target ID"));
        assertEquals(1, stale.exitCode());
        assertTrue(stale.stderr().contains("Unknown Zolt update target"));
        assertEquals(1, blocked.exitCode());
        assertTrue(blocked.stderr().contains("not updateable"));
    }

    @Test
    void dryRunAndSameVersionNoOpWriteNothing() throws IOException {
        Path project = writeProject(tempDir.resolve("no-writes"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");
        String original = Files.readString(project.resolve("zolt.toml"));

        Result dryRun = run(project, () -> {}, exactArgs(
                target, "1.1.0", "--dry-run", "--format", "json", "--schema-version", "2"));
        Result noOp = run(project, () -> {}, exactArgs(
                target, "1.0.0", "--format", "json", "--schema-version", "2"));

        assertEquals(0, dryRun.exitCode());
        assertTrue(dryRun.stdout().contains("\"dryRun\": true"));
        assertTrue(dryRun.stdout().contains("\"changed\": true"));
        assertEquals(0, noOp.exitCode());
        assertTrue(noOp.stdout().contains("\"changed\": false"));
        assertTrue(noOp.stdout().contains("\"applied\": false"));
        assertEquals(original, Files.readString(project.resolve("zolt.toml")));
        assertFalse(Files.exists(project.resolve("zolt.lock")));
    }

    @Test
    void workspaceRootRoutesOnlyTheOpaqueTargetsOwningMemberAndSection() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"
                members = ["apps/api", "modules/core"]
                """);
        Path api = writeProject(root.resolve("apps/api"), """
                [dependencies]
                "com.example:shared" = "1.0.0"
                [test.dependencies]
                "com.example:shared" = "1.0.0"
                """);
        Path core = writeProject(root.resolve("modules/core"), """
                [dependencies]
                "com.example:shared" = "1.0.0"
                """);
        UpdateTarget target = target(
                api, "apps/api/zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");

        Result result = run(root, () -> {}, exactArgs(
                target, "1.1.0", "--format", "json", "--schema-version", "2", "--no-resolve"));

        assertEquals(0, result.exitCode(), result.stderr());
        String apiManifest = Files.readString(api.resolve("zolt.toml"));
        assertTrue(apiManifest.contains("[dependencies]\n\"com.example:shared\" = \"1.1.0\""));
        assertTrue(apiManifest.contains("[test.dependencies]\n\"com.example:shared\" = \"1.0.0\""));
        assertTrue(Files.readString(core.resolve("zolt.toml")).contains("\"com.example:shared\" = \"1.0.0\""));
        assertTrue(result.stdout().contains("\"manifestPath\": \"apps/api/zolt.toml\""));
        assertTrue(result.stdout().contains("\"changedFiles\": [\n    \"apps/api/zolt.toml\""));
    }

    @Test
    void rootMemberAndLegacyWorkspaceRouteExactTargets() throws IOException {
        Path rootMember = writeProject(tempDir.resolve("root-member"), """
                [dependencies]
                "com.example:root" = "1.0.0"

                [workspace]
                name = "root-member"
                members = ["."]
                """);
        UpdateTarget rootTarget = target(
                rootMember, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");

        Result rootResult = run(rootMember, () -> {}, exactArgs(rootTarget, "1.1.0", "--no-resolve"));

        assertEquals(0, rootResult.exitCode(), rootResult.stderr());
        assertTrue(Files.readString(rootMember.resolve("zolt.toml"))
                .contains("\"com.example:root\" = \"1.1.0\""));

        Path legacy = tempDir.resolve("legacy-workspace");
        Path member = writeProject(legacy.resolve("apps/api"), """
                [dependencies]
                "com.example:legacy" = "2.0.0"
                """);
        Files.writeString(legacy.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "legacy"
                members = ["apps/api"]
                """);
        UpdateTarget legacyTarget = target(
                member, "apps/api/zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");

        Result legacyResult = run(legacy, () -> {}, exactArgs(legacyTarget, "2.1.0", "--no-resolve"));

        assertEquals(0, legacyResult.exitCode(), legacyResult.stderr());
        assertTrue(Files.readString(member.resolve("zolt.toml"))
                .contains("\"com.example:legacy\" = \"2.1.0\""));
    }

    @Test
    void executionRebuildsTargetAndTurnsAConcurrentDestinationIntoNoOp() throws IOException {
        Path project = writeProject(tempDir.resolve("revalidate-noop"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        Path manifest = project.resolve("zolt.toml");
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");
        Runnable concurrent = () -> replace(manifest, "\"com.example:lib\" = \"1.0.0\"", "\"com.example:lib\" = \"1.1.0\"");

        Result result = run(project, concurrent, exactArgs(
                target, "1.1.0", "--format", "json", "--schema-version", "2"));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"from\": \"1.1.0\""));
        assertTrue(result.stdout().contains("\"changed\": false"));
        assertTrue(result.stdout().contains("\"applied\": false"));
        assertFalse(Files.exists(project.resolve("zolt.lock")));
    }

    @Test
    void executionFailsClosedWhenTheSelectedTargetDisappears() throws IOException {
        Path project = writeProject(tempDir.resolve("disappeared"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        Path manifest = project.resolve("zolt.toml");
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");
        Runnable concurrent = () -> replace(
                manifest,
                "\"com.example:lib\" = \"1.0.0\"",
                "\"com.example:replacement\" = \"1.0.0\"");

        Result result = run(project, concurrent, exactArgs(
                target, "1.1.0", "--format", "json", "--schema-version", "2", "--no-resolve"));

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("Unknown Zolt update target"));
        assertTrue(Files.readString(manifest).contains("com.example:replacement"));
        assertFalse(Files.readString(manifest).contains("com.example:lib"));
    }

    @Test
    void executionFailsClosedWhenAStandaloneProjectBecomesAMalformedWorkspace() throws IOException {
        Path project = writeProject(tempDir.resolve("malformed-workspace-race"), """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        Path manifest = project.resolve("zolt.toml");
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]");
        Runnable concurrent = () -> replace(
                manifest,
                "[dependencies]",
                "[workspace]\nname = \"broken\"\nmembers = [\"missing-member\"]\n\n[dependencies]");

        Result result = run(project, concurrent, exactArgs(
                target, "1.1.0", "--format", "json", "--schema-version", "2", "--no-resolve"));

        assertEquals(1, result.exitCode());
        assertTrue(
                result.stdout().contains("\"status\": \"failed\""),
                () -> "stdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr());
        assertTrue(Files.readString(manifest).contains("\"com.example:lib\" = \"1.0.0\""));
        assertFalse(Files.exists(project.resolve("zolt.lock")));
    }

    @Test
    void identicalRegeneratedLockIsOmittedFromActualChangedFiles() throws IOException {
        Path project = writeProject(tempDir.resolve("unchanged-lock"), """
                [versions]
                unused = "1.0.0"

                [dependencies]
                """);
        Path cache = project.resolve("cache");
        ResolveService resolveService = new ResolveService();
        Path manifest = project.resolve("zolt.toml");
        replace(manifest, "unused = \"1.0.0\"", "unused = \"2.0.0\"");
        resolveService.resolve(project, parser.parse(project.resolve("zolt.toml")), cache);
        replace(manifest, "unused = \"2.0.0\"", "unused = \"1.0.0\"");
        String originalLock = Files.readString(project.resolve("zolt.lock"));
        UpdateTarget target = target(project, "zolt.toml", OutdatedSurface.VERSION_ALIAS, "[versions]");

        Result result = run(
                project,
                () -> {},
                resolveService,
                exactArgs(target, "2.0.0", "--format", "json", "--schema-version", "2", "--cache-root", cache.toString()));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"resolved\": true"));
        assertTrue(result.stdout().contains("\"changedFiles\": [\n    \"zolt.toml\"\n  ]"), result.stdout());
        assertEquals(originalLock, Files.readString(project.resolve("zolt.lock")));
    }

    private Result run(Path project, Runnable beforeExecution, String... arguments) {
        return run(project, beforeExecution, null, arguments);
    }

    private Result run(
            Path project,
            Runnable beforeExecution,
            ResolveService resolveService,
            String... arguments) {
        VersionDiscovery forbiddenDiscovery = (repositories, group, artifact, offline) -> {
            throw new AssertionError("Exact update must not perform metadata discovery.");
        };
        UpdateCommand command = new UpdateCommand(
                parser,
                new ZoltTomlWriter(),
                resolveService,
                new UpdateEngine(forbiddenDiscovery),
                beforeExecution);
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setExecutionExceptionHandler((exception, parsedCommandLine, parseResult) -> 1);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        List<String> full = new ArrayList<>(List.of(arguments));
        full.add("--directory");
        full.add(project.toString());
        int exitCode = commandLine.execute(full.toArray(String[]::new));
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private UpdateTarget target(
            Path project,
            String manifestPath,
            OutdatedSurface surface,
            String section) {
        return catalog.collect(parser.parse(project.resolve("zolt.toml")), manifestPath, "zolt.lock").stream()
                .filter(candidate -> candidate.surface() == surface && candidate.section().equals(section))
                .findFirst()
                .orElseThrow();
    }

    private static String[] exactArgs(UpdateTarget target, String version, String... extra) {
        return exactArgs(target.targetId().toString(), version, extra);
    }

    private static String[] exactArgs(String targetId, String version, String... extra) {
        List<String> arguments = new ArrayList<>(List.of("--target-id", targetId, "--to", version));
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    private static Path writeProject(Path directory, String body) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                """.formatted(directory.getFileName(), body));
        return directory;
    }

    private static void replace(Path path, String before, String after) {
        try {
            Files.writeString(path, Files.readString(path).replace(before, after));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
