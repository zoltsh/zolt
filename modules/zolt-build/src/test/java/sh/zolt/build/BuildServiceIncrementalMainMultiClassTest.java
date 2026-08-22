package sh.zolt.build;

import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.config;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.source;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceIncrementalMainMultiClassTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void innerClassBodyChangeUsesIncrementalMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path outer = source(projectDir, "src/main/java/com/example/Outer.java", """
                package com.example;

                public final class Outer {
                    public String message() {
                        return new Inner().value() + " one";
                    }

                    static final class Inner {
                        String value() {
                            return "inner";
                        }
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(outer, """
                package com.example;

                public final class Outer {
                    public String message() {
                        return new Inner().value() + " two";
                    }

                    static final class Inner {
                        String value() {
                            return "inner";
                        }
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Outer.class")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Outer$Inner.class")));
    }

    @Test
    void removedInnerClassIsDeletedDuringIncrementalMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path outer = source(projectDir, "src/main/java/com/example/Outer.java", """
                package com.example;

                public final class Outer {
                    public String message() {
                        return new Inner().value();
                    }

                    static final class Inner {
                        String value() {
                            return "inner";
                        }
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Outer$Inner.class")));
        Files.writeString(outer, """
                package com.example;

                public final class Outer {
                    public String message() {
                        return "flat";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Outer.class")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/Outer$Inner.class")));
    }

    @Test
    void innerClassPublicAbiChangeRecompilesDependentThatReferencesInner() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path api = source(projectDir, "src/main/java/com/example/api/Api.java", """
                package com.example.api;

                public final class Api {
                    public static final class Inner {
                        public String value() {
                            return "one";
                        }
                    }

                    public String outer() {
                        return "outer";
                    }
                }
                """);
        source(projectDir, "src/main/java/com/example/app/UseInner.java", """
                package com.example.app;

                import com.example.api.Api;

                public final class UseInner {
                    public void use() {
                        new Api.Inner().value();
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(api, """
                package com.example.api;

                public final class Api {
                    public static final class Inner {
                        public int value() {
                            return 2;
                        }
                    }

                    public String outer() {
                        return "outer";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
    }
}
