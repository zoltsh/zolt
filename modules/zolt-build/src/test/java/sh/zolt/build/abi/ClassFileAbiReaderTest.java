package sh.zolt.build.abi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClassFileAbiReaderTest {
    @TempDir
    private Path tempDir;

    private final ClassFileAbiReader reader = new ClassFileAbiReader();

    @Test
    void readsPublicAbiPackageAbiAndReferencedClasses() throws IOException {
        Path source = source("src/main/java/com/example/Api.java", """
                package com.example;

                import java.io.IOException;
                import java.io.Serializable;
                import java.util.List;

                public class Api extends Base implements Serializable {
                    public String value(List<String> input) throws IOException {
                        return input.toString();
                    }

                    protected int size() {
                        return 1;
                    }

                    String packageValue() {
                        return "package";
                    }

                    private String hidden() {
                        return "hidden";
                    }
                }

                class Base {
                }
                """);
        Path output = compile(source);

        ClassFileAbi abi = reader.read(output.resolve("com/example/Api.class"));

        assertEquals("com.example.Api", abi.binaryName());
        assertEquals("Api.java", abi.sourceFileName().orElseThrow());
        assertEquals("com.example.Base", abi.superName().orElseThrow());
        assertEquals(List.of("java.io.Serializable"), abi.interfaces());
        assertTrue(abi.referencedClasses().contains("java.io.IOException"));
        assertTrue(abi.referencedClasses().contains("java.util.List"));
        assertTrue(abi.referencedClasses().contains("java.lang.String"));
        assertNotEquals(abi.abiHash(), abi.packagePrivateAbiHash());
    }

    @Test
    void publicAbiIgnoresPrivateImplementationChanges() throws IOException {
        Path source = source("src/main/java/com/example/Api.java", """
                package com.example;

                public class Api {
                    public String value() {
                        return hidden();
                    }

                    private String hidden() {
                        return "one";
                    }
                }
                """);
        Path firstOutput = compile(source);
        String firstAbi = reader.read(firstOutput.resolve("com/example/Api.class")).abiHash();
        Files.writeString(source, """
                package com.example;

                public class Api {
                    public String value() {
                        return hidden();
                    }

                    private String hidden() {
                        return "two";
                    }
                }
                """);
        Path secondOutput = compileTo(source, tempDir.resolve("target/second-classes"));

        assertEquals(firstAbi, reader.read(secondOutput.resolve("com/example/Api.class")).abiHash());
    }

    @Test
    void abiChangesWhenPublicSignatureChanges() throws IOException {
        Path source = source("src/main/java/com/example/Api.java", """
                package com.example;

                public class Api {
                    public String value() {
                        return "one";
                    }
                }
                """);
        String firstAbi = reader.read(compile(source).resolve("com/example/Api.class")).abiHash();
        Files.writeString(source, """
                package com.example;

                public class Api {
                    public int value() {
                        return 1;
                    }
                }
                """);

        String secondAbi = reader.read(compileTo(source, tempDir.resolve("target/second-classes"))
                .resolve("com/example/Api.class")).abiHash();

        assertNotEquals(firstAbi, secondAbi);
    }

    @Test
    void abiChangesWhenPublicConstantValueChanges() throws IOException {
        Path source = source("src/main/java/com/example/Api.java", """
                package com.example;

                public class Api {
                    public static final String VERSION = "one";
                }
                """);
        String firstAbi = reader.read(compile(source).resolve("com/example/Api.class")).abiHash();
        Files.writeString(source, """
                package com.example;

                public class Api {
                    public static final String VERSION = "two";
                }
                """);

        String secondAbi = reader.read(compileTo(source, tempDir.resolve("target/second-classes"))
                .resolve("com/example/Api.class")).abiHash();

        assertNotEquals(firstAbi, secondAbi);
    }

    @Test
    void abiChangesWhenCompileVisibleAnnotationChanges() throws IOException {
        Path source = source("src/main/java/com/example/Api.java", """
                package com.example;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                @interface Marker {
                    String value();
                }

                public class Api {
                    @Marker("one")
                    public String value() {
                        return "one";
                    }
                }
                """);
        String firstAbi = reader.read(compile(source).resolve("com/example/Api.class")).abiHash();
        Files.writeString(source, """
                package com.example;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                @interface Marker {
                    String value();
                }

                public class Api {
                    @Marker("two")
                    public String value() {
                        return "one";
                    }
                }
                """);

        String secondAbi = reader.read(compileTo(source, tempDir.resolve("target/second-classes"))
                .resolve("com/example/Api.class")).abiHash();

        assertNotEquals(firstAbi, secondAbi);
    }

    @Test
    void readsRecordsEnumsAndNestedClasses() throws IOException {
        Path source = source("src/main/java/com/example/Shapes.java", """
                package com.example;

                public final class Shapes {
                    public record Point(int x, int y) {
                    }

                    public enum Kind {
                        CIRCLE
                    }
                }
                """);
        Path output = compile(source);

        assertEquals("com.example.Shapes$Point", reader.read(output.resolve("com/example/Shapes$Point.class")).binaryName());
        assertEquals("com.example.Shapes$Kind", reader.read(output.resolve("com/example/Shapes$Kind.class")).binaryName());
    }

    private Path compile(Path source) {
        return compileTo(source, tempDir.resolve("target/classes"));
    }

    private Path compileTo(Path source, Path output) {
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                output);
        return output;
    }

    private Path source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
