package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeModelGeneratedSourcesTest {
    private final IdeModelService service = new IdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsDeclaredGeneratedSourceRootsForEditors() throws IOException {
        Path projectDir = tempDir.resolve("generated-sources");
        Path mainInput = projectDir.resolve("src/main/openapi/api.yaml");
        Path mainOutput = projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java");
        Path testInput = projectDir.resolve("src/test/fixtures/schema.json");
        Path testOutput = projectDir.resolve("target/generated/test-sources/fixtures/com/example/GeneratedFixture.java");
        Files.createDirectories(mainInput.getParent());
        Files.createDirectories(mainOutput.getParent());
        Files.createDirectories(testInput.getParent());
        Files.createDirectories(testOutput.getParent());
        Files.writeString(mainInput, "openapi: 3.1.0\n");
        Files.writeString(mainOutput, "package com.example; public final class GeneratedApi {}\n");
        Files.writeString(testInput, "{}\n");
        Files.writeString(testOutput, "package com.example; public final class GeneratedFixture {}\n");
        Files.setLastModifiedTime(mainInput, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(mainOutput, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(testInput, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(testOutput, FileTime.fromMillis(2_000));
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "generated-sources"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.main.openapi]
                kind = "declared-root"
                language = "java"
                output = "target/generated/sources/openapi"
                inputs = ["src/main/openapi/api.yaml"]

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                output = "target/generated/test-sources/fixtures"
                inputs = ["src/test/fixtures/schema.json"]
                required = false
                clean = true
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "generated-main-openapi",
                "main",
                "java",
                root.resolve("target/generated/sources/openapi"),
                true)));
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "generated-test-fixtures",
                "test",
                "java",
                root.resolve("target/generated/test-sources/fixtures"),
                true)));
        assertEquals(List.of(
                new IdeModel.GeneratedSourceInfo(
                        "generated-main-openapi",
                        "generated-main-openapi",
                        "main",
                        "declared-root",
                        "java",
                        root.resolve("target/generated/sources/openapi"),
                        List.of(root.resolve("src/main/openapi/api.yaml")),
                        true,
                        false,
                        "external-declared-root",
                        "main-compile",
                        "fresh",
                        true,
                        true,
                        "",
                        "",
                        ""),
                new IdeModel.GeneratedSourceInfo(
                        "generated-test-fixtures",
                        "generated-test-fixtures",
                        "test",
                        "declared-root",
                        "java",
                        root.resolve("target/generated/test-sources/fixtures"),
                        List.of(root.resolve("src/test/fixtures/schema.json")),
                        false,
                        true,
                        "zolt-owned-clean",
                        "test-compile",
                        "fresh",
                        true,
                        true,
                        "",
                        "",
                        "")),
                model.generatedSources());
        String json = new IdeModelJsonWriter().write(model);
        assertTrue(json.contains("\"generatedSources\": ["));
        assertTrue(json.contains("\"id\": \"generated-main-openapi\""));
        assertTrue(json.contains("\"id\": \"generated-test-fixtures\""));
        assertTrue(json.contains("\"ownership\": \"external-declared-root\""));
        assertTrue(json.contains("\"compileLane\": \"test-compile\""));
        assertTrue(json.contains("\"freshness\": \"fresh\""));
    }

    @Test
    void exportsOpenApiToolVersionRefForGeneratedSourceEvidence() throws IOException {
        Path projectDir = tempDir.resolve("openapi-tool-version-ref");
        Path input = projectDir.resolve("src/main/openapi/public-api.yaml");
        Path output = projectDir.resolve("target/generated/sources/openapi/public-api/com/example/PublicApi.java");
        Files.createDirectories(input.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(input, "openapi: 3.1.0\n");
        Files.writeString(output, "package com.example; public final class PublicApi {}\n");
        Files.setLastModifiedTime(input, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(output, FileTime.fromMillis(2_000));
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "openapi-tool-version-ref"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [versions]
                openapi = "7.11.0"

                [generated.openapiTool]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        IdeModel.GeneratedSourceInfo generatedSource = model.generatedSources().getFirst();
        assertEquals("generated-main-public-api", generatedSource.id());
        assertEquals("zolt-owned-openapi", generatedSource.ownership());
        assertEquals("org.openapitools:openapi-generator-cli:7.11.0", generatedSource.toolArtifact());
        assertEquals("openapi", generatedSource.toolVersionRef());

        String json = new IdeModelJsonWriter().write(model);
        assertTrue(json.contains("\"toolArtifact\": \"org.openapitools:openapi-generator-cli:7.11.0\""));
        assertTrue(json.contains("\"toolVersionRef\": \"openapi\""));
    }

    @Test
    void exportsProtobufGeneratedSourceRootsForEditors() throws IOException {
        Path projectDir = tempDir.resolve("protobuf-generated-sources");
        Path input = projectDir.resolve("src/main/proto/greeter.proto");
        Path output = projectDir.resolve("target/generated/sources/protobuf/com/example/greeter/HelloRequest.java");
        Files.createDirectories(input.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(input, "syntax = \"proto3\";\npackage com.example.greeter;\nmessage HelloRequest {}\n");
        Files.writeString(output, "package com.example.greeter; public final class HelloRequest {}\n");
        Files.setLastModifiedTime(input, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(output, FileTime.fromMillis(2_000));
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "protobuf-generated-sources"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "generated-main-greeter",
                "main",
                "java",
                root.resolve("target/generated/sources/protobuf"),
                true)));
        IdeModel.GeneratedSourceInfo generatedSource = model.generatedSources().getFirst();
        assertEquals("protobuf", generatedSource.kind());
        assertEquals("zolt-owned-protobuf", generatedSource.ownership());
        assertEquals("fresh", generatedSource.freshness());
        assertEquals("main-compile", generatedSource.compileLane());
    }
}
