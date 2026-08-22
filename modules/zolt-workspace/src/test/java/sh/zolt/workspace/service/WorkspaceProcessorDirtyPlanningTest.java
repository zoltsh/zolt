package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A member that runs annotation processors is dirty for reasons, not by standing rule.
 *
 * <p>Stage 0 used to mark every processor-bearing member dirty on every command, because a processor
 * turns inputs into sources that are themselves inputs and nothing in the recorded state described
 * that second half. The state now describes it: the processor classpath's identity and the generated
 * tree the processor emitted are both observed, so the member is left alone until one of them moves.
 *
 * <p>What this rests on is that a processor is a function of its declared inputs — the assumption
 * Gradle and Bazel also make. A processor that reads an undeclared file or environment variable is
 * outside the contract, and {@code ZOLT_WORKSPACE_PARANOID} does not help there either.
 */
final class WorkspaceProcessorDirtyPlanningTest extends WorkspaceBuildServiceTestSupport {
    private static final String GENERATED =
            "apps/api/target/generated/sources/annotations/com/acme/generated/Greeting.java";

    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @BeforeEach
    void buildOnce() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/greeting-processor"]
                """);
        member("modules/greeting-processor", "greeting-processor", "");
        processor("generated");
        source(
                "modules/greeting-processor/src/main/resources/META-INF/services/"
                        + "javax.annotation.processing.Processor",
                "com.acme.greeting.GreetingProcessor\n");
        member("apps/api", "api", """

                [dependencies.processor]
                "com.acme:greeting-processor" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.generated.Greeting;

                public final class Api {
                    public static String message() {
                        return Greeting.message();
                    }
                }
                """);
        build();
        assertTrue(Files.exists(tempDir.resolve(GENERATED)));
        // A second build settles the outputs the first one created into the file table: a copied
        // resource or generated source does not exist when the cold command reads its inputs, so it
        // is first recorded by the command after the one that wrote it. "Warm" starts here.
        build();
    }

    @Test
    void aProcessorBearingMemberIsLeftAloneWhenNothingMoved() {
        WorkspaceBuildResult result = build();

        assertEquals(0, result.executionMetrics().memberPipelineInvocations());
        assertEquals(0, result.executionMetrics().membersAdmitted());
        assertEquals(0, result.executionMetrics().filesHashed());
        assertTrue(result.executionMetrics().filesReused() > 0);
        assertEquals(2, result.mainCompilationSkippedCount());
    }

    @Test
    void anAnnotatedSourceEditRebuildsTheMemberAndRefreshesTheGeneratedTree() throws IOException {
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.generated.Greeting;

                public final class Api {
                    public static String message() {
                        return Greeting.message() + "!";
                    }
                }
                """);

        WorkspaceBuildResult result = build();

        assertEquals(1, result.mainCompilationExecutedCount());
        assertTrue(Files.exists(tempDir.resolve(GENERATED)));
        assertEquals(0, build().executionMetrics().memberPipelineInvocations());
    }

    /**
     * A processor edit confined to a method body: its signatures are untouched, so an ABI comparison
     * would miss it, but every source it emits changes.
     */
    @Test
    void aProcessorImplementationEditRebuildsItsConsumer() throws IOException {
        processor("regenerated");

        WorkspaceBuildResult result = build();

        assertEquals(2, result.mainCompilationExecutedCount());
        assertTrue(Files.readString(tempDir.resolve(GENERATED)).contains("regenerated"));
        assertEquals(0, build().executionMetrics().memberPipelineInvocations());
    }

    @Test
    void aHandEditedGeneratedSourceRebuildsTheMember() throws IOException {
        Path generated = tempDir.resolve(GENERATED);
        Files.writeString(generated, Files.readString(generated).replace("generated", "tampered"));

        WorkspaceBuildResult result = build();

        assertEquals(1, result.mainCompilationExecutedCount());
        assertEquals(0, build().executionMetrics().memberPipelineInvocations());
    }

    @Test
    void aDeletedGeneratedTreeRebuildsTheMember() throws IOException {
        Files.delete(tempDir.resolve(GENERATED));

        WorkspaceBuildResult result = build();

        assertEquals(1, result.mainCompilationExecutedCount());
        assertTrue(Files.exists(tempDir.resolve(GENERATED)));
    }

    private WorkspaceBuildResult build() {
        return service.build(
                tempDir,
                tempDir.resolve("cache"),
                false,
                new WorkspaceSelectionRequest(true, List.of()));
    }

    /** The processor member, emitting {@code message} from a method body. */
    private void processor(String message) throws IOException {
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
                                writer.write("    public static String message() { return \\"MESSAGE\\"; }\\n");
                                writer.write("}\\n");
                            }
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                        return false;
                    }
                }
                """.replace("MESSAGE", message));
    }
}
