package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.generatedSourceConfig;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckGeneratedSourcesCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkGeneratedSourcesReportsNoDeclaredSteps() throws IOException {
        Path projectDir = tempDir.resolve("check-no-generated-sources");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-no-generated-sources"));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok generated-sources check-no-generated-sources No declared generated-source steps require validation."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesPassesForExistingDeclaredRoots() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-ok");
        Files.createDirectories(projectDir.resolve("target/generated/sources/openapi/com/example"));
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/api.yaml"), "openapi: 3.1.0\n");
        Files.writeString(projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java"), """
                package com.example;
                public final class GeneratedApi {}
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-ok")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok generated-sources [generated.main.openapi] Generated source root `target/generated/sources/openapi` is declared"));
        assertTrue(result.stdout().contains("exported as IDE source root `generated-main-openapi`"));
        assertTrue(result.stdout().contains("ownership `external-declared-root` and freshness `fresh`"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesReportsZoltOwnedProtobufRoots() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-protobuf");
        Files.createDirectories(projectDir.resolve("target/generated/sources/protobuf/com/example/greeter"));
        Files.createDirectories(projectDir.resolve("src/main/proto"));
        Files.writeString(projectDir.resolve("src/main/proto/greeter.proto"), """
                syntax = "proto3";
                package com.example.greeter;
                message HelloRequest {}
                """);
        Files.writeString(projectDir.resolve("target/generated/sources/protobuf/com/example/greeter/HelloRequest.java"), """
                package com.example.greeter;
                public final class HelloRequest {}
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-protobuf")
                + """

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                output = "target/generated/sources/protobuf"
                inputs = ["src/main/proto/greeter.proto"]
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok generated-sources [generated.main.greeter] Generated source root `target/generated/sources/protobuf` is declared"));
        assertTrue(result.stdout().contains("ownership `zolt-owned-protobuf` and freshness `fresh`"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesReportsMalformedPaths() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-invalid-path");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-invalid-path")
                + generatedSourceConfig("main", "openapi", "../generated/openapi", "src/main/openapi/api.yaml", true));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error generated-sources [generated.main.openapi].output Invalid generated source output path `../generated/openapi`."));
        assertTrue(result.stdout().contains("next: Use a project-relative path under the project directory."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesReportsMissingRequiredOutputs() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-missing-required");
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/api.yaml"), "openapi: 3.1.0\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-missing-required")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error generated-sources [generated.main.openapi] Generated source root `target/generated/sources/openapi` is missing."));
        assertTrue(result.stdout().contains("next: Run the generator that produces it, commit the generated sources"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesReportsMissingInputs() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-missing-input");
        Files.createDirectories(projectDir.resolve("target/generated/sources/openapi/com/example"));
        Files.writeString(projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java"), """
                package com.example;
                public final class GeneratedApi {}
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-missing-input")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error generated-sources [generated.main.openapi] Generated source input `src/main/openapi/api.yaml` is missing."));
        assertTrue(result.stdout().contains("next: Create the input file or update [generated.main.openapi].inputs."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesReportsStaleOutputs() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-stale");
        Path outputFile = projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java");
        Path inputFile = projectDir.resolve("src/main/openapi/api.yaml");
        Files.createDirectories(outputFile.getParent());
        Files.createDirectories(inputFile.getParent());
        Files.writeString(outputFile, """
                package com.example;
                public final class GeneratedApi {}
                """);
        Files.writeString(inputFile, "openapi: 3.1.0\n");
        Files.setLastModifiedTime(outputFile, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(inputFile, FileTime.fromMillis(2_000));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-stale")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error generated-sources [generated.main.openapi] Generated source root `target/generated/sources/openapi` is stale; one or more declared inputs are newer."));
        assertTrue(result.stdout().contains("next: Regenerate the source root or update [generated.main.openapi].inputs."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkGeneratedSourcesSkipsOptionalMissingOutputs() throws IOException {
        Path projectDir = tempDir.resolve("check-generated-sources-optional");
        Files.createDirectories(projectDir.resolve("src/test/fixtures"));
        Files.writeString(projectDir.resolve("src/test/fixtures/schema.json"), "{}\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-generated-sources-optional")
                + generatedSourceConfig("test", "fixtures", "target/generated/test-sources/fixtures", "src/test/fixtures/schema.json", false));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "generated-sources");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("skip generated-sources [generated.test.fixtures] Optional generated source root `target/generated/test-sources/fixtures` is missing."));
        assertTrue(result.stdout().contains("next: Generate it when needed, or set required = true"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceGeneratedSourcesIdentifyMemberPaths() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-generated-sources");
        Path apiDir = workspaceDir.resolve("modules/api");
        Path implDir = workspaceDir.resolve("modules/impl");
        Files.createDirectories(apiDir.resolve("target/generated/sources/openapi"));
        Files.createDirectories(apiDir.resolve("src/main/openapi"));
        Files.createDirectories(implDir);
        Files.writeString(apiDir.resolve("src/main/openapi/api.yaml"), "openapi: 3.1.0\n");
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-generated-sources"

                [workspace.members]
                include = ["modules/api", "modules/impl"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true));
        Files.writeString(implDir.resolve("zolt.toml"), memberConfig("impl"));

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "modules/api",
                "--cwd", workspaceDir.toString(),
                "--check", "generated-sources");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok generated-sources modules/api [generated.main.openapi] Generated source root `target/generated/sources/openapi` is declared"));
        assertEquals("", result.stderr());
    }
}
