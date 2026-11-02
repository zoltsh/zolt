package sh.zolt.build.compile.kotlin.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.build.compile.KotlinCompilerArgumentTestSupport.options;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.classpath.Classpath;

final class KotlinAnnotationProcessorOptionsTest {
    @Test
    void parsesOnlyProcessorOptionsAndCanonicalizesKeyOrder() {
        KotlinAnnotationProcessorOptions options = KotlinAnnotationProcessorOptions.parse(
                List.of("-Werror", "-Az.last=two=parts", "-Aa.first=", "-parameters"),
                KotlinCompilationScope.MAIN);

        assertEquals(
                Map.of("a.first", "", "z.last", "two=parts"),
                options.values());
        assertEquals(List.of("a.first", "z.last"), new ArrayList<>(options.values().keySet()));
    }

    @Test
    void mapsOptionsOnlyFromTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of("-Amain.option=main"),
                List.of("-Atest.option=test"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of("-Amain.option=main"),
                List.of("-Atest.option=test"));

        assertEquals(
                Map.of("main.option", "main"),
                main.policy().annotationProcessing().processorOptions());
        assertEquals(
                Map.of("test.option", "test"),
                test.policy().annotationProcessing().processorOptions());
    }

    @Test
    void encodesTheExactDeterministicMapReadByKapt() throws IOException {
        KotlinAnnotationProcessorOptions options = new KotlinAnnotationProcessorOptions(
                Map.of("z.last", "after", "a.first", "before"));

        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(
                Base64.getDecoder().decode(options.encoded())))) {
            assertEquals(2, input.readInt());
            assertEquals("a.first", input.readUTF());
            assertEquals("before", input.readUTF());
            assertEquals("z.last", input.readUTF());
            assertEquals("after", input.readUTF());
        }
    }

    @Test
    void rejectsValuelessInvalidAndDuplicateOptionsActionably() {
        for (List<String> arguments : List.of(
                List.of("-Aexample.option"),
                List.of("-Abad-key=value"),
                List.of("-Aexample.option=first", "-Aexample.option=second"))) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> KotlinAnnotationProcessorOptions.parse(
                            arguments,
                            KotlinCompilationScope.TEST));

            assertTrue(failure.getMessage().contains("[compiler.test].args"));
            assertTrue(failure.getMessage().contains("-A"));
        }
    }

    @Test
    void rejectsValuesBeyondKaptsModifiedUtfLimitActionably() {
        KotlinAnnotationProcessorOptions options = new KotlinAnnotationProcessorOptions(
                Map.of("example.option", "x".repeat(70_000)));

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                options::encoded);

        assertTrue(failure.getMessage().contains("modified UTF-8 limit"));
    }

    @Test
    void requiresTheMatchingProcessorLaneWhenOptionsArePresent() {
        KotlinAnnotationProcessorOptions options = new KotlinAnnotationProcessorOptions(
                Map.of("example.message", "hello"));
        for (KotlinCompilationScope scope : KotlinCompilationScope.values()) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options.requireProcessorClasspath(new Classpath(List.of()), scope));

            String dependencyPath = scope == KotlinCompilationScope.MAIN
                    ? "[dependencies.processor]"
                    : "[dependencies.test-processor]";
            assertTrue(failure.getMessage().contains(dependencyPath));
            assertTrue(failure.getMessage().contains("-Akey=value"));
        }
        options.requireProcessorClasspath(
                new Classpath(List.of(Path.of("processor.jar"))),
                KotlinCompilationScope.MAIN);
    }
}
