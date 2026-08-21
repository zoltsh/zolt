package sh.zolt.cli.build;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.generatedSourceConfig;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlanCommandGeneratedNativeTest {
    @TempDir
    private Path tempDir;

    @Test
    void planShowsTypedOpenApiGenerationEvidenceWithoutExecutingIt() throws IOException {
        Path projectDir = tempDir.resolve("plan-openapi-generated-source");
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/public-api.yaml"), "openapi: 3.1.0\n");
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-openapi-generated-source") + """

                [versions]
                openapi = "7.11.0"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi"

                [generated.openapiPresets.spring-api]
                generator = "spring"
                library = "spring-boot"
                options = { interfaceOnly = "true" }

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                preset = "spring-api"
                options = { hideGenerationTimestamp = "true" }
                """);

        CommandResult result = execute("plan", "--target", "build", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("- generate-main-public-api [generated-source] ready"));
        assertTrue(result.stdout().contains("kind: openapi"));
        assertTrue(result.stdout().contains("ownership: zolt-owned-openapi"));
        assertTrue(result.stdout().contains("toolArtifact: org.openapitools:openapi-generator-cli:7.11.0"));
        assertTrue(result.stdout().contains("toolVersionRef: openapi"));
        assertTrue(result.stdout().contains("toolFingerprint: "));
        assertTrue(result.stdout().contains("optionsFingerprint: "));
        assertEquals("", result.stderr());
    }

    @Test
    void planReportsStaleGeneratedSourceOutputs() throws IOException {
        Path projectDir = tempDir.resolve("plan-stale-generated-source");
        Path input = projectDir.resolve("src/main/openapi/api.yaml");
        Path output = projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java");
        Files.createDirectories(input.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(input, "openapi: 3.1.0\n");
        Files.writeString(output, "package com.example; public final class GeneratedApi {}\n");
        Files.setLastModifiedTime(output, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(input, FileTime.fromMillis(2_000));
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("plan-stale-generated-source")
                + generatedSourceConfig("main", "openapi", "target/generated/sources/openapi", "src/main/openapi/api.yaml", true));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("plan", "--target", "build", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("freshness: stale"));
        assertTrue(result.stdout().contains("blocker stale-generated-source-output"));
        assertTrue(result.stdout().contains("Required generated source output `target/generated/sources/openapi` is older"));
    }

    @Test
    void planNativeJsonReportsSpringBootNativeBlockersWithoutRunningTools() throws IOException {
        Path projectDir = tempDir.resolve("plan-native");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "plan-native"
                version = "1.0.0"
                group = "com.example"
                java = 21
                main = "com.example.DemoApplication"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "3.3.6"

                [dependencies]
                "org.springframework.boot:spring-boot-starter-web" = "3.3.6"

                [framework.spring-boot]
                native = true

                [native]
                name = "plan-native"
                args = ["--no-fallback"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "3.3.6"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "3.3.6"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """);

        CommandResult result = execute(
                "plan",
                "--target", "native",
                "--format", "json",
                "--native-image", projectDir.resolve("missing/native-image").toString(),
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"target\": \"native\""));
        assertTrue(result.stdout().contains("\"id\": \"spring-boot-native-intent\""));
        assertTrue(result.stdout().contains("\"id\": \"spring-aot-tooling\""));
        assertTrue(result.stdout().contains("\"code\": \"missing-spring-aot-tooling\""));
        assertTrue(result.stdout().contains("\"code\": \"missing-spring-aot-output\""));
        assertTrue(result.stdout().contains("\"code\": \"missing-native-image\""));
        assertTrue(result.stdout().contains("\"nativeArgs: [--no-fallback]\""));
        assertFalse(Files.exists(projectDir.resolve("target/spring-aot")));
        assertFalse(Files.exists(projectDir.resolve("target/native")));
        assertEquals("", result.stderr());
    }
}
