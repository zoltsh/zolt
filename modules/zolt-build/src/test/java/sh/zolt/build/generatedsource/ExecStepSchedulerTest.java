package sh.zolt.build.generatedsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProtobufGenerationSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ExecStepSchedulerTest {
    private static final Path ROOT = Path.of("/project").toAbsolutePath();

    @Test
    void ordersProducerBeforeConsumerFromInputUnderOutput() {
        GeneratedSourceStep install = step("install", "web/node_modules", List.of("web/package.json"));
        GeneratedSourceStep build = step("build", "web/dist", List.of("web/node_modules", "web/src"));

        List<GeneratedSourceStep> ordered =
                ExecStepScheduler.order(ROOT, BuildSettings.defaults(), "main", List.of(build, install));

        assertEquals(List.of("install", "build"), ordered.stream().map(GeneratedSourceStep::id).toList());
    }

    @Test
    void breaksTiesAlphabeticallyById() {
        GeneratedSourceStep zebra = step("zebra", "target/generated/zebra", List.of("src/z"));
        GeneratedSourceStep alpha = step("alpha", "target/generated/alpha", List.of("src/a"));

        List<GeneratedSourceStep> ordered =
                ExecStepScheduler.order(ROOT, BuildSettings.defaults(), "main", List.of(zebra, alpha));

        assertEquals(List.of("alpha", "zebra"), ordered.stream().map(GeneratedSourceStep::id).toList());
    }

    @Test
    void rejectsCyclicInputOutputDependency() {
        GeneratedSourceStep a = step("a", "target/generated/a", List.of("target/generated/b/out.txt"));
        GeneratedSourceStep b = step("b", "target/generated/b", List.of("target/generated/a/out.txt"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> ExecStepScheduler.order(ROOT, BuildSettings.defaults(), "main", List.of(a, b)));

        assertTrue(exception.getMessage().contains("cyclic input/output dependency"), exception.getMessage());
        assertTrue(exception.getMessage().contains("a") && exception.getMessage().contains("b"));
    }

    @Test
    void allowsInputUnderCompiledClassesForPostCompileScheduling() {
        GeneratedSourceStep step = step("meta", "target/generated/meta", List.of("target/classes/app/Main.class"));

        List<GeneratedSourceStep> ordered =
                ExecStepScheduler.order(ROOT, BuildSettings.defaults(), "main", List.of(step));

        assertEquals(List.of("meta"), ordered.stream().map(GeneratedSourceStep::id).toList());
    }

    @Test
    void rejectsPostCompileProducerFeedingPreCompileConsumer() {
        GeneratedSourceStep post = projectStep("gen", "target/generated/gen", List.of("target/classes"));
        GeneratedSourceStep pre = step("weave", "target/generated/weave", List.of("target/generated/gen/out.txt"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> ExecStepScheduler.order(ROOT, BuildSettings.defaults(), "main", List.of(post, pre)));

        assertTrue(exception.getMessage().contains("post-compile"), exception.getMessage());
        assertTrue(exception.getMessage().contains("weave") && exception.getMessage().contains("gen"));
    }

    private static GeneratedSourceStep step(String id, String output, List<String> inputs) {
        return execStep(id, output, inputs, new ExecToolSettings(
                "jvm",
                List.of(new ExecToolCoordinate("com.example:tool", Optional.of("1.0.0"), Optional.empty())),
                "com.example.Main"));
    }

    private static GeneratedSourceStep projectStep(String id, String output, List<String> inputs) {
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.EXEC,
                "java",
                output,
                inputs,
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                new ExecGenerationSettings(
                        "project",
                        ExecToolSettings.project("com.example.Gen"),
                        List.of(),
                        ProducesLane.RESOURCES,
                        Optional.empty(),
                        Map.of(),
                        "content"));
    }

    private static GeneratedSourceStep execStep(String id, String output, List<String> inputs, ExecToolSettings tool) {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                tool.runner(),
                tool,
                List.of(),
                ProducesLane.JAVA_SOURCES,
                Optional.empty(),
                Map.of(),
                "content");
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.EXEC,
                "java",
                output,
                inputs,
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }
}
