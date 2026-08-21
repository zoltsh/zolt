package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.update.OutdatedSurface;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateTarget;
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

final class ConstraintLicensePolicyUpdateCommandTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;


    @Test
    void policyUpdatePreservesCompleteLicensePolicySource() throws IOException {
        Path project = writeProject(tempDir.resolve("policy"));
        Path manifest = project.resolve("zolt.toml");
        String original = Files.readString(manifest);

        Result result = run(
                project,
                discovery(),
                "--format", "json",
                "--no-resolve");

        assertEquals(0, result.exitCode(), () -> result.stdout() + result.stderr());
        assertEquals(updatedSource(original), Files.readString(manifest));
    }

    @Test
    void exactUpdatePreservesCompleteLicensePolicySource() throws IOException {
        Path project = writeProject(tempDir.resolve("exact"));
        Path manifest = project.resolve("zolt.toml");
        String original = Files.readString(manifest);
        UpdateTarget target = new UpdateTargetCatalog()
                .collect(LOADER.document(manifest).authored(), "zolt.toml", "zolt.lock").stream()
                .filter(candidate -> candidate.surface() == OutdatedSurface.DEPENDENCY_CONSTRAINT)
                .findFirst()
                .orElseThrow();

        Result result = run(
                project,
                forbiddenDiscovery(),
                "--target-id", target.targetId().toString(),
                "--to", "1.1.0",
                "--format", "json",
                "--schema-version", "2",
                "--no-resolve");

        assertEquals(0, result.exitCode(), () -> result.stdout() + result.stderr());
        assertEquals(updatedSource(original), Files.readString(manifest));
    }

    private Result run(Path project, VersionDiscovery discovery, String... arguments) {
        UpdateCommand command =
                new UpdateCommand(
                MANIFESTS, null, new UpdateEngine(discovery));
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        List<String> full = new java.util.ArrayList<>(List.of(arguments));
        full.add("--directory");
        full.add(project.toString());
        int exitCode = commandLine.execute(full.toArray(String[]::new));
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private static Path writeProject(Path project) throws IOException {
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "constraint-license-policy"
                version = "0.1.0"
                group = "com.example"
                java = 21


                [dependencies.constraints]
                "com.example:lib" = { version = "1.0.0", reason = "supported baseline" }

                # retain this complete policy block byte-for-byte
                [dependencies.policy.licenses]
                allow = ["MIT", "Apache-2.0"]
                deny = ["GPL-3.0-only"]
                unknown = "fail"

                [dependencies.license-exceptions."org.example:transitive"]
                allow = ["BSD-3-Clause"]
                version = "2.0.0"
                reason = "reviewed exception"
                """);
        return project;
    }

    private static String updatedSource(String original) {
        return original.replace("version = \"1.0.0\", reason", "version = \"1.1.0\", reason");
    }

    private static VersionDiscovery discovery() {
        MetadataDiscovery listing = new MetadataDiscovery(
                true,
                List.of("1.0.0", "1.1.0"),
                Map.of("1.0.0", "central", "1.1.0", "central"),
                List.of());
        return (repositories, group, artifact, offline) -> listing;
    }

    private static VersionDiscovery forbiddenDiscovery() {
        return (repositories, group, artifact, offline) -> {
            throw new AssertionError("Exact update must not perform metadata discovery.");
        };
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
