package sh.zolt.generated;

import static sh.zolt.generated.GeneratedSourceEvidenceServiceTestSupport.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedSourceEvidenceServiceTest {
    @TempDir
    private Path tempDir;

    private final GeneratedSourceEvidenceService service = new GeneratedSourceEvidenceService();

    @Test
    void reportsDeterministicLifecycleEvidenceForDeclaredRoots() throws IOException {
        Path freshInput = write(tempDir, "src/main/openapi/api.yaml", "openapi: 3.1.0\n", 1_000);
        Path freshOutput = write(tempDir, "target/generated/sources/openapi/com/example/GeneratedApi.java", """
                package com.example;
                public final class GeneratedApi {}
                """, 2_000);
        Path staleInput = write(tempDir, "src/test/fixtures/schema.json", "{}\n", 4_000);
        Path staleOutput = write(tempDir, "target/generated/test-sources/fixtures/com/example/Fixture.java", """
                package com.example;
                public final class Fixture {}
                """, 3_000);

        BuildSettings build = BuildSettings.defaults().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false)),
                List.of(
                        new GeneratedSourceStep(
                                "fixtures",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/test-sources/fixtures",
                                List.of("src/test/fixtures/schema.json"),
                                true,
                                true),
                        new GeneratedSourceStep(
                                "missing-input",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/test-sources/missing-input",
                                List.of("src/test/fixtures/missing.json"),
                                true,
                                false)));

        List<GeneratedSourceEvidence> evidence = service.evidence(tempDir, build);

        assertEquals(List.of(
                "generated-main-openapi",
                "generated-test-fixtures",
                "generated-test-missing-input"), evidence.stream().map(GeneratedSourceEvidence::id).toList());
        assertEquals(List.of("fresh", "stale", "input-missing"), evidence.stream().map(GeneratedSourceEvidence::freshness).toList());
        assertEquals(List.of(
                "external-declared-root",
                "zolt-owned-clean",
                "external-declared-root"), evidence.stream().map(GeneratedSourceEvidence::ownership).toList());
        assertEquals(List.of("main-compile", "test-compile", "test-compile"), evidence.stream().map(GeneratedSourceEvidence::compileLane).toList());
        assertEquals(freshInput.toAbsolutePath().normalize(), evidence.get(0).inputs().getFirst());
        assertEquals(freshOutput.getParent().getParent().getParent().toAbsolutePath().normalize(), evidence.get(0).output());
        assertEquals(staleInput.toAbsolutePath().normalize(), evidence.get(1).inputs().getFirst());
        assertEquals(staleOutput.getParent().getParent().getParent().toAbsolutePath().normalize(), evidence.get(1).output());
    }

    @Test
    void reportsProtobufToolingAndOptionFingerprints() throws IOException {
        Path input = write(tempDir, "src/main/proto/greeter.proto", "message Hello {}\n", 1_000);
        Path output = write(tempDir, "target/generated/sources/protobuf/com/example/greeter/Hello.java", """
                package com.example.greeter;
                public final class Hello {}
                """, 2_000);
        BuildSettings build = new ManifestProjectConfigLoader().load("""
                [project]
                name = "protobuf-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                protoc = "4.28.3"
                grpc = "1.68.1"

                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersionRef = "protoc"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersionRef = "grpc"

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                javaPackage = "com.example.greeter"
                grpc = false
                """).build();

        GeneratedSourceEvidence evidence = service.evidence(tempDir, build).getFirst();

        assertEquals("generated-main-greeter", evidence.id());
        assertEquals("zolt-owned-protobuf", evidence.ownership());
        assertEquals("fresh", evidence.freshness());
        assertEquals("main-compile", evidence.compileLane());
        assertEquals("protoc=com.google.protobuf:protoc:4.28.3;grpc=io.grpc:protoc-gen-grpc-java:1.68.1", evidence.toolArtifact());
        assertEquals(64, evidence.toolFingerprint().length());
        assertEquals(64, evidence.optionsFingerprint().length());
        assertFalse(evidence.toolFingerprint().equals(evidence.optionsFingerprint()));
        assertEquals(input.toAbsolutePath().normalize(), evidence.inputs().getFirst());
        assertEquals(output.getParent().getParent().getParent().getParent().toAbsolutePath().normalize(), evidence.output());
    }
}
