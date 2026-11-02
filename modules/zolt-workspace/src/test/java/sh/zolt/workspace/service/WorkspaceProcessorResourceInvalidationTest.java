package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkspaceProcessorResourceInvalidationTest extends WorkspaceBuildServiceTestSupport {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @BeforeEach
    void createWorkspace() throws IOException {
        workspace("""
                [workspace]
                name = "processor-resources"
                [workspace.members]
                include = ["processor", "consumer"]
                """);
        member("processor", "processor", "");
        member("consumer", "consumer", """
                [dependencies.processor]
                "com.acme:processor" = { workspace = true }
                """);
        source("processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor",
                "com.acme.Processor\n");
        source("processor/src/main/resources/message.txt", "first");
        source("processor/src/main/java/com/acme/Processor.java", """
                package com.acme;
                import java.io.IOException;
                import java.io.Writer;
                import java.nio.charset.StandardCharsets;
                import java.util.Set;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                @SupportedAnnotationTypes("*")
                @SupportedSourceVersion(SourceVersion.RELEASE_21)
                public final class Processor extends AbstractProcessor {
                    private boolean generated;
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (generated || round.processingOver()) return false;
                        generated = true;
                        try (var input = getClass().getResourceAsStream("/message.txt")) {
                            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                            try (Writer output = processingEnv.getFiler()
                                    .createSourceFile("com.acme.Generated").openWriter()) {
                                output.write("package com.acme; public class Generated { public static final String VALUE = "
                                        + (char) 34 + value + (char) 34 + "; }");
                            }
                        } catch (IOException failure) { throw new RuntimeException(failure); }
                        return false;
                    }
                }
                """);
        source("consumer/src/main/java/com/acme/Consumer.java", """
                package com.acme;
                public class Consumer { public static String value() { return Generated.VALUE; } }
                """);
        build("consumer");
        assertTrue(generated().contains("first"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processorResourceChangeRegeneratesConsumer(boolean buildProcessorFirst) throws IOException {
        source("processor/src/main/resources/message.txt", "second");
        if (buildProcessorFirst) {
            build("processor");
        }

        WorkspaceBuildResult result = build("consumer");

        assertTrue(generated().contains("second"), "processor resource changed but generated source was stale");
        assertEquals(1, result.mainCompilationExecutedCount());
        assertEquals(0, build("consumer").executionMetrics().memberPipelineInvocations());
    }

    @Test
    void runtimeDependencyChangeInSeparatelyBuiltProcessorRegeneratesConsumer() throws Exception {
        Files.delete(tempDir.resolve("processor/src/main/resources/message.txt"));
        runtimeResource("1.0.0", "external-first");
        build("consumer");
        assertTrue(generated().contains("external-first"));
        runtimeResource("2.0.0", "external-second");
        build("processor");

        WorkspaceBuildResult result = build("consumer");

        assertTrue(generated().contains("external-second"), "processor runtime dependency changed but generated source was stale");
        assertEquals(1, result.mainCompilationExecutedCount());
        assertEquals(0, build("consumer").executionMetrics().memberPipelineInvocations());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processorUsesCurrentImplementationOfCompileDependency(boolean buildHelperFirst) throws Exception {
        workspace("""
                [workspace]
                name = "processor-compile-dependency"
                [workspace.members]
                include = ["processor", "helper", "consumer"]
                """);
        member("helper", "helper", "");
        member("consumer", "consumer", """
                [dependencies]
                "com.acme:helper" = { workspace = true }
                [dependencies.processor]
                "com.acme:processor" = { workspace = true }
                """);
        String helper = "package com.acme; public class Helper { public static String value() { return \"first\"; } }";
        source("helper/src/main/java/com/acme/Helper.java", helper);
        var processor = tempDir.resolve("processor/src/main/java/com/acme/Processor.java");
        Files.writeString(processor, Files.readString(processor)
                .replace("try (var input = getClass().getResourceAsStream(\"/message.txt\")) {", "try {")
                .replace("new String(input.readAllBytes(), StandardCharsets.UTF_8)",
                        "(String) Class.forName(\"com.acme.Helper\", true, getClass().getClassLoader())"
                                + ".getMethod(\"value\").invoke(null)")
                .replace("catch (IOException failure)", "catch (Exception failure)"));
        Files.delete(tempDir.resolve("zolt.lock"));
        build("consumer");
        assertTrue(generated().contains("first"));
        source("helper/src/main/java/com/acme/Helper.java", helper.replace("first", "other"));
        if (buildHelperFirst) {
            build("helper");
        }

        build("consumer");

        assertTrue(generated().contains("other"), "processor ran against stale compile dependency implementation");
    }

    private void runtimeResource(String version, String message) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (var jar = new java.util.jar.JarOutputStream(bytes)) {
            jar.putNextEntry(new java.util.jar.JarEntry("message.txt"));
            jar.write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        String relative = "blobs/v2/sha256/" + hash + "/messages-" + version + ".jar";
        var artifact = tempDir.resolve("cache").resolve(relative);
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, bytes.toByteArray());
        var lock = tempDir.resolve("zolt.lock");
        String content = Files.readString(lock);
        int marker = content.indexOf("\n# processor-runtime\n");
        if (marker >= 0) {
            content = content.substring(0, marker);
        }
        Files.writeString(lock, content + """

                # processor-runtime
                [[package]]
                id = "com.acme:messages"
                version = "%s"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "%s"
                jarSha256 = "%s"
                members = ["processor"]
                dependencies = []
                """.formatted(version, relative, hash));
    }

    private WorkspaceBuildResult build(String member) {
        return service.build(tempDir, tempDir.resolve("cache"), false,
                new WorkspaceSelectionRequest(false, List.of(member)));
    }

    private String generated() throws IOException {
        return Files.readString(tempDir.resolve(
                "consumer/target/generated/sources/annotations/com/acme/Generated.java"));
    }
}
