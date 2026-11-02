package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real mixed compiler proof for Kotlin sources owned by the typed Protobuf generator. */
final class BuildServiceProtobufGeneratedKotlinIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void generatesCompilesSkipsAndInvalidatesKotlinMainSources() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                import com.example_$.protocol.GreeterGrpc;
                import com.example_$.protocol.HelloReply;

                public final class JavaApi {
                    public static String generatedApi() {
                        return HelloReply.getDefaultInstance().getClass().getSimpleName()
                                + ":" + GreeterGrpc.serviceName();
                    }
                }
                """);
        writeProto("com.example.schema");
        BuildService service = new BuildService();

        BuildResult first = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();

        assertEquals(3, first.sourceCount());
        assertEquals("full", first.mainCompilationMode());
        assertEquals("kotlin-main-sources", first.mainIncrementalFallbackReason());
        assertTrue(Files.isRegularFile(generatedSource("HelloReply.kt")));
        assertTrue(Files.isRegularFile(generatedSource("GreeterGrpc.kt")));
        assertTrue(Files.isRegularFile(classFile("HelloReply.class")));
        assertEquals(
                "HelloReply:com.example.schema.Greeter",
                invoke("com.example.JavaApi", "generatedApi", artifacts));

        BuildResult warm = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();
        assertTrue(warm.mainCompilationSkipped());

        writeProto("com.example.changed");
        BuildResult changed = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();
        assertFalse(changed.mainCompilationSkipped());
        assertEquals("full", changed.mainCompilationMode());
        assertEquals(
                "HelloReply:com.example.changed.Greeter",
                invoke("com.example.JavaApi", "generatedApi", artifacts));
    }

    private void writeProto(String protoPackage) throws IOException {
        source("src/main/proto/greeter.proto", """
                syntax = "proto3";
                package %s;

                message HelloReply {}
                service Greeter {}
                """.formatted(protoPackage));
    }

    private Path generatedSource(String name) {
        return projectDir.resolve(
                "target/generated/sources/protobuf/com/example_$/protocol/" + name);
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example_$/protocol/" + name);
    }

    private Object invoke(
            String className,
            String methodName,
            KotlinCompilerIntegrationArtifacts.Prepared artifacts) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : artifacts.applicationClasspath()) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            return Class.forName(className, true, loader).getMethod(methodName).invoke(null);
        }
    }

    private Path source(String relativePath, String content) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "protobuf-generated-kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.kotlin]
                version = "2.2.0"

                [generated.main.protocol]
                kind = "protobuf"
                language = "kotlin"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                javaPackage = "com.example_$.protocol"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """);
    }
}
