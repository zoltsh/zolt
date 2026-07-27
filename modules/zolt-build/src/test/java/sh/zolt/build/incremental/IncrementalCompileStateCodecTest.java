package sh.zolt.build.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IncrementalCompileStateCodecTest {
    private final IncrementalCompileStateCodec codec = new IncrementalCompileStateCodec();

    @Test
    void stateRoundTripsDeterministically() {
        Path project = Path.of("/workspace/demo");
        IncrementalCompileState state = new IncrementalCompileState(
                "main",
                project,
                project.resolve("target/classes"),
                project.resolve("target/generated/sources/annotations"),
                "compiler-hash",
                "fingerprint-hash",
                List.of("processor-classpath"),
                List.of("src/main/java"),
                List.of("target/generated/sources/openapi"),
                List.of(new IncrementalCompileState.ClasspathEntry(
                        project.resolve("lib/b.jar"), 42L, 123L, "hash-b")),
                List.of(new IncrementalCompileState.ClasspathEntry(
                        project.resolve("processor/a.jar"), 84L, 456L, "hash-a")),
                List.of(new IncrementalCompileState.SourceRecord(
                        project.resolve("src/main/java/com/example/App.java"),
                        project.resolve("src/main/java"),
                        Optional.of("openapi"),
                        "source-hash",
                        "com.example",
                        List.of("com.example.App"),
                        List.of(project.resolve("target/classes/com/example/App.class")),
                        List.of("com.example.Dependency"),
                        List.of(project.resolve("target/generated/sources/annotations/com/example/AppMeta.java")),
                        List.of(project.resolve("target/classes/com/example/AppMeta.class")),
                        List.of(project.resolve("target/classes/com/example/App.meta")))),
                List.of(new IncrementalCompileState.ClassRecord(
                        "com.example.App",
                        project.resolve("target/classes/com/example/App.class"),
                        "class-hash",
                        "abi-hash",
                        "package-abi-hash",
                        33,
                        Optional.of("java.lang.Object"),
                        List.of("java.io.Serializable"))),
                Map.of(
                        "com.example.Zeta",
                        List.of(project.resolve("src/main/java/com/example/App.java")),
                        "com.example.Alpha",
                        List.of(project.resolve("src/main/java/com/example/App.java"))),
                false);

        String formatted = codec.format(state);
        IncrementalCompileState parsed = codec.parse(formatted).orElseThrow();

        assertEquals(state, parsed);
        assertEquals(formatted, codec.format(parsed));
    }

    @Test
    void rejectsUnsupportedOrCorruptState() {
        assertTrue(codec.parse("version=999\n").isEmpty());
        assertTrue(codec.parse("""
                version=1
                scope=main
                source\t%%%not-base64%%%
                """).isEmpty());
    }
}
