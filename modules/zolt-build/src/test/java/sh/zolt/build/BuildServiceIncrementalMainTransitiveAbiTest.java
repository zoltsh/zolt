package sh.zolt.build;

import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.config;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.source;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceIncrementalMainTransitiveAbiTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    // A -> B -> C chain. B extends A, so changing A's method return type flips the
    // covariant bridge that javac synthesizes in B: recompiling the otherwise
    // unchanged B mutates B's own public ABI, which must in turn recompile C.
    // A single validation wave would recompile B but never revisit C.
    @Test
    void transitiveAbiChangeRecompilesGrandDependent() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path a = source(projectDir, "src/main/java/com/example/A.java", """
                package com.example;

                public class A {
                    public Object make() {
                        return this;
                    }
                }
                """);
        source(projectDir, "src/main/java/com/example/B.java", """
                package com.example;

                public class B extends A {
                    @Override
                    public B make() {
                        return this;
                    }
                }
                """);
        source(projectDir, "src/main/java/com/example/C.java", """
                package com.example;

                public final class C {
                    public B use(B b) {
                        return b.make();
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(a, """
                package com.example;

                public class A {
                    public A make() {
                        return this;
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(3, result.sourceCount());
    }
}
