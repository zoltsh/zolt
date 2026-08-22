package sh.zolt.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtobufGeneratedSourceServiceTest {
    @TempDir
    private Path tempDir;

    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();
    private final ProtobufGeneratedSourceService service = new ProtobufGeneratedSourceService();

    @Test
    void generatesDeterministicJavaSourcesForMessagesAndGrpcServices() throws IOException {
        Path proto = tempDir.resolve("src/main/proto/greeter.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, """
                syntax = "proto3";
                package com.example.greeter;
                option java_package = "com.example.greeter.api";

                message HelloReply {
                  string message = 1;
                }

                message HelloRequest {
                  string name = 1;
                }

                service Greeter {
                  rpc SayHello (HelloRequest) returns (HelloReply);
                }
                """);
        var config = manifestLoader.load(config("""
                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                """));

        service.generateMain(tempDir, config);

        Path output = tempDir.resolve("target/generated/sources/protobuf/com/example/greeter/api");
        assertTrue(Files.exists(output.resolve("HelloReply.java")));
        assertTrue(Files.exists(output.resolve("HelloRequest.java")));
        assertTrue(Files.exists(output.resolve("GreeterGrpc.java")));
        assertTrue(Files.readString(output.resolve("GreeterGrpc.java"))
                .contains("return \"com.example.greeter.Greeter\";"));
        assertTrue(Files.readString(tempDir.resolve("target/generated/sources/protobuf/META-INF/zolt/protobuf/greeter.descriptor"))
                .contains("messages=HelloReply,HelloRequest"));
    }

    @Test
    void parserRejectsArbitraryProtocPlugins() {
        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> manifestLoader.load(config("""
                        [generated.main.greeter]
                        kind = "protobuf"
                        language = "java"
                        inputs = ["src/main/proto/greeter.proto"]
                        output = "target/generated/sources/protobuf"
                        plugins = { custom = "protoc-gen-custom" }
                        """)));

        assertTrue(exception.getMessage().contains("Unknown manifest section `[generated.main.greeter.plugins]`"));
    }

    @Test
    void generationFailsClearlyForMissingInputs() {
        var config = manifestLoader.load(config("""
                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/missing.proto"]
                output = "target/generated/sources/protobuf"
                """));

        GeneratedSourceException exception = assertThrows(
                GeneratedSourceException.class,
                () -> service.generateMain(tempDir, config));

        assertTrue(exception.getMessage().contains("Protobuf input src/main/proto/missing.proto does not exist"));
        assertTrue(exception.getMessage().contains("[generated.main.greeter]"));
    }

    @Test
    void parserRecordsProtobufKindAndSettings() {
        var config = manifestLoader.load(config("""
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
                """));

        var step = config.build().generatedMainSources().getFirst();
        assertEquals(GeneratedSourceKind.PROTOBUF, step.kind());
        assertEquals("com.google.protobuf:protoc", step.protobuf().protocCoordinate().orElseThrow());
        assertEquals("4.28.3", step.protobuf().protocVersion().orElseThrow());
        assertEquals("protoc", step.protobuf().protocVersionRef().orElseThrow());
        assertEquals("io.grpc:protoc-gen-grpc-java", step.protobuf().grpcPluginCoordinate().orElseThrow());
        assertEquals("1.68.1", step.protobuf().grpcPluginVersion().orElseThrow());
        assertEquals("grpc", step.protobuf().grpcPluginVersionRef().orElseThrow());
        assertEquals("com.example.greeter", step.protobuf().javaPackage().orElseThrow());
        assertEquals(false, step.protobuf().grpc());
    }

    @Test
    void generationRejectsUnsafeProtoJavaPackageBeforeWritingOutput() throws IOException {
        String[] unsafePackages = {
            ".tmp.pwn",
            "../pwn",
            "com..example",
            "com.example; class Evil {}",
            "com.example\nclass Evil {}"
        };
        for (int index = 0; index < unsafePackages.length; index++) {
            Path project = tempDir.resolve("unsafe-java-package-" + index);
            Path proto = project.resolve("src/main/proto/greeter.proto");
            Files.createDirectories(proto.getParent());
            Files.writeString(proto, """
                    syntax = "proto3";
                    option java_package = "%s";
                    message HelloReply {}
                    """.formatted(unsafePackages[index]));
            Path marker = project.resolve("target/generated/sources/protobuf/marker.txt");
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "keep");
            var config = manifestLoader.load(config("""
                    [generated.main.greeter]
                    kind = "protobuf"
                    language = "java"
                    inputs = ["src/main/proto/greeter.proto"]
                    output = "target/generated/sources/protobuf"
                    """));

            GeneratedSourceException exception = assertThrows(
                    GeneratedSourceException.class,
                    () -> service.generateMain(project, config));

            assertTrue(exception.getMessage().contains("option java_package"), exception.getMessage());
            assertTrue(!exception.getMessage().contains("Evil"), exception.getMessage());
            assertEquals("keep", Files.readString(marker));
        }
    }

    @Test
    void generationRejectsUnsafeProtoPackageWhenUsedAsJavaPackage() throws IOException {
        Path proto = tempDir.resolve("src/main/proto/greeter.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, """
                syntax = "proto3";
                package com..example;
                message HelloReply {}
                """);
        var config = manifestLoader.load(config("""
                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                """));

        GeneratedSourceException exception = assertThrows(
                GeneratedSourceException.class,
                () -> service.generateMain(tempDir, config));

        assertTrue(exception.getMessage().contains("Protobuf input src/main/proto/greeter.proto proto package"));
        assertTrue(exception.getMessage().contains("protobuf dotted identifier"));
    }

    @Test
    void generationRejectsUnsafeProtoPackageEvenWithValidJavaPackage() throws IOException {
        String[] unsafeProtoPackages = {
            "com.example\"evil",
            "com.example\\\\evil",
            "com.example+evil",
            "com.example evil"
        };
        for (int index = 0; index < unsafeProtoPackages.length; index++) {
            Path project = tempDir.resolve("unsafe-proto-package-" + index);
            Path proto = project.resolve("src/main/proto/greeter.proto");
            Files.createDirectories(proto.getParent());
            Files.writeString(proto, """
                    syntax = "proto3";
                    package %s;
                    option java_package = "com.example.grpc";
                    service Greeter {}
                    """.formatted(unsafeProtoPackages[index]));
            Path marker = project.resolve("target/generated/sources/protobuf/marker.txt");
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "keep");
            var config = manifestLoader.load(config("""
                    [generated.main.greeter]
                    kind = "protobuf"
                    language = "java"
                    inputs = ["src/main/proto/greeter.proto"]
                    output = "target/generated/sources/protobuf"
                    """));

            GeneratedSourceException exception = assertThrows(
                    GeneratedSourceException.class,
                    () -> service.generateMain(project, config));

            assertTrue(exception.getMessage().contains("proto package"), exception.getMessage());
            assertTrue(exception.getMessage().contains("protobuf dotted identifier"), exception.getMessage());
            assertEquals("keep", Files.readString(marker));
        }
    }

    @Test
    void generateTestCleansStaleOutputAndUsesDefaultPackages() throws IOException {
        Path proto = tempDir.resolve("src/test/proto/echo.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, """
                syntax = "proto3";
                message EchoRequest {}
                service Echo {}
                """);
        Path stale = tempDir.resolve("target/generated/test-sources/protobuf/stale.txt");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");
        var config = manifestLoader.load(config("""
                [generated.test.echo]
                kind = "protobuf"
                language = "java"
                inputs = ["src/test/proto/echo.proto"]
                output = "target/generated/test-sources/protobuf"
                """));

        service.generateTest(tempDir, config);

        Path output = tempDir.resolve("target/generated/test-sources/protobuf/generated/protobuf");
        assertTrue(!Files.exists(stale));
        assertTrue(Files.exists(output.resolve("EchoRequest.java")));
        assertTrue(Files.readString(output.resolve("EchoGrpc.java")).contains("return \"Echo\";"));
        assertTrue(Files.readString(tempDir.resolve(
                        "target/generated/test-sources/protobuf/META-INF/zolt/protobuf/echo.descriptor"))
                .contains("services=Echo"));
    }

    @Test
    void javaStringLiteralEscapesGeneratedSourceValues() {
        assertEquals("\"quote\\\"backslash\\\\newline\\ntab\\t\"", JavaSourceLiterals.string("quote\"backslash\\newline\ntab\t"));
    }

    @Test
    void generationBuildsPackagePathUnderOutputRoot() throws IOException {
        Path proto = tempDir.resolve("src/main/proto/greeter.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, """
                syntax = "proto3";
                option java_package = "com.example_$._internal";
                message HelloReply {}
                """);
        var config = manifestLoader.load(config("""
                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                """));

        service.generateMain(tempDir, config);

        Path output = tempDir.resolve("target/generated/sources/protobuf/com/example_$/_internal");
        assertTrue(Files.exists(output.resolve("HelloReply.java")));
        assertTrue(Files.readString(output.resolve("HelloReply.java")).startsWith("package com.example_$._internal;"));
    }

    private static String config(String generated) {
        return """
                [project]
                name = "protobuf-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s
                """.formatted(generated);
    }
}
