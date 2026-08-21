package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.ClasspathSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof for : a consumer member declares another workspace member as an
 * annotation processor via {@code [annotationProcessors] "x" = { workspace = true }}. The
 * processor member is compiled first, runs during the consumer's compile (generating a source that
 * the consumer references), and neither the processor member's output nor its transitive
 * dependencies leak onto the consumer's compile/runtime/test classpaths.
 */
final class WorkspaceAnnotationProcessorBuildTest extends WorkspaceBuildServiceTestSupport {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @Test
    void workspaceProcessorRunsDuringConsumerCompileAndStaysIsolated() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/greeting-processor"]
                """);

        // Processor member: a real javax.annotation.processing.Processor that generates a source.
        member("modules/greeting-processor", "greeting-processor", "");
        source("modules/greeting-processor/src/main/java/com/acme/greeting/GreetingProcessor.java", """
                package com.acme.greeting;

                import java.io.IOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.annotation.processing.SupportedSourceVersion;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import javax.tools.JavaFileObject;

                @SupportedAnnotationTypes("*")
                @SupportedSourceVersion(SourceVersion.RELEASE_17)
                public final class GreetingProcessor extends AbstractProcessor {
                    private boolean generated;

                    @Override
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                        if (generated || roundEnv.processingOver()) {
                            return false;
                        }
                        generated = true;
                        try {
                            JavaFileObject file =
                                    processingEnv.getFiler().createSourceFile("com.acme.generated.Greeting");
                            try (Writer writer = file.openWriter()) {
                                writer.write("package com.acme.generated;\\n");
                                writer.write("public final class Greeting {\\n");
                                writer.write("    public static String message() { return \\"generated\\"; }\\n");
                                writer.write("}\\n");
                            }
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                        return false;
                    }
                }
                """);
        // META-INF/services entry so javac discovers the processor through the service loader.
        source(
                "modules/greeting-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor",
                "com.acme.greeting.GreetingProcessor\n");

        // Consumer member: uses the workspace processor and references the generated type.
        member("apps/api", "api", """

                [dependencies.processor]
                "com.acme:greeting-processor" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.generated.Greeting;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Greeting.message();
                    }
                }
                """);

        WorkspaceBuildResult result = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);

        // Build order: processor member compiled before the consumer.
        assertEquals(List.of("modules/greeting-processor", "apps/api"), result.members().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());

        // The processor ran: the generated source AND its compiled class exist in the consumer output.
        assertTrue(Files.exists(tempDir.resolve(
                "apps/api/target/generated/sources/annotations/com/acme/generated/Greeting.java")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/generated/Greeting.class")));
        // The consumer source that references the generated type compiled successfully.
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));

        // Isolation: the processor member's compiled output is on the consumer's processor path ONLY.
        Path processorOutput = tempDir.resolve("modules/greeting-processor/target/classes").normalize();
        ClasspathSet consumerClasspaths = result.members().stream()
                .filter(member -> member.member().equals("apps/api"))
                .findFirst()
                .orElseThrow()
                .classpaths();
        assertTrue(consumerClasspaths.processor().entries().contains(processorOutput));
        assertFalse(consumerClasspaths.compile().entries().contains(processorOutput));
        assertFalse(consumerClasspaths.runtime().entries().contains(processorOutput));
        assertFalse(consumerClasspaths.test().entries().contains(processorOutput));
    }
}
