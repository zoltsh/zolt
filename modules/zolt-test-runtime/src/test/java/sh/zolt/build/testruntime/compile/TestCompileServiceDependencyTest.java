package sh.zolt.build.testruntime.compile;

import static sh.zolt.build.testruntime.compile.TestCompileServiceTestSupport.config;
import static sh.zolt.build.testruntime.compile.TestCompileServiceTestSupport.createHelperJar;
import static sh.zolt.build.testruntime.compile.TestCompileServiceTestSupport.source;
import static sh.zolt.build.testruntime.compile.TestCompileServiceTestSupport.writeLockfile;
import static sh.zolt.build.TestContentAddressedLockSupport.cachedJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCompileServiceDependencyTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void testCompileClasspathIncludesTestDependencies() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path helperJar = cacheRoot.resolve("com/example/helper/1.0.0/helper-1.0.0.jar");
        createHelperJar(projectDir, helperJar);
        writeLockfile(projectDir, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:helper"
                version = "1.0.0"
                lane = "test"
                resolvedScope = "test"

                [[package]]
                id = "com.example:helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "com/example/helper/1.0.0/helper-1.0.0.jar"
                dependencies = []
                """);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", """
                package com.example;

                import com.example.helper.Helper;

                public final class MainTest {
                    public String message() {
                        return Helper.message();
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), cacheRoot);

        assertEquals(1, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
    }

    @Test
    void testCompileClasspathIncludesProvidedDependenciesButTestRuntimeDoesNot() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path helperJar = cacheRoot.resolve("com/example/helper/1.0.0/helper-1.0.0.jar");
        createHelperJar(projectDir, helperJar);
        writeLockfile(projectDir, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:helper"
                version = "1.0.0"
                lane = "provided"
                resolvedScope = "provided"

                [[package]]
                id = "com.example:helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "com/example/helper/1.0.0/helper-1.0.0.jar"
                dependencies = []
                """);
        Path lockedHelperJar = cachedJar(projectDir, "com.example:helper");
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", """
                package com.example;

                import com.example.helper.Helper;

                public final class MainTest {
                    public String message() {
                        return Helper.message();
                    }
                }
                """);

        TestCompileResultWithClasspaths result =
                testCompileService.compileTestsWithClasspaths(projectDir, config(), cacheRoot);

        assertEquals(1, result.testCompileResult().sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
        assertTrue(result.classpaths().testCompile().entries().contains(lockedHelperJar),
                "provided dependency must be on the test compile classpath");
        assertEquals(List.of(), result.classpaths().test().entries(),
                "provided dependency must stay off the test runtime classpath");
    }

    @Test
    void compileTestsWithClasspathsReturnsReusedClasspaths() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path helperJar = cacheRoot.resolve("com/example/helper/1.0.0/helper-1.0.0.jar");
        createHelperJar(projectDir, helperJar);
        writeLockfile(projectDir, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:helper"
                version = "1.0.0"
                lane = "test"
                resolvedScope = "test"

                [[package]]
                id = "com.example:helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "com/example/helper/1.0.0/helper-1.0.0.jar"
                dependencies = []
                """);
        Path lockedHelperJar = cachedJar(projectDir, "com.example:helper");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");

        TestCompileResultWithClasspaths result =
                testCompileService.compileTestsWithClasspaths(projectDir, config(), cacheRoot);

        assertEquals(1, result.testCompileResult().sourceCount());
        assertEquals(List.of(lockedHelperJar), result.classpaths().test().entries());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
    }

    @Test
    void testCompilerErrorsAreSurfacedClearly() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/BrokenTest.java", """
                package com.example;

                public final class BrokenTest {
                    missing
                }
                """);

        JavacException exception = assertThrows(
                JavacException.class,
                () -> testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("javac failed with exit code"));
        assertTrue(exception.getMessage().contains("BrokenTest.java"));
    }
}
