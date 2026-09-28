package sh.zolt.build.abi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClassFileAbiSoundnessTest {
    @TempDir
    private Path tempDir;

    private final ClassFileAbiReader reader = new ClassFileAbiReader();

    @Test
    void genericSignatureChangeInvalidatesAbiWhenConstantPoolIndexIsStable() throws IOException {
        assertAbiChanges(
                "Signature",
                "com/example/Api.class",
                "src/main/java/com/example/Api.java",
                """
                package com.example;

                import java.util.List;

                public class Api {
                    public List<String> values() { return null; }
                }
                """,
                """
                package com.example;

                import java.util.List;

                public class Api {
                    public List<Integer> values() { return null; }
                }
                """);
    }

    @Test
    void declaredExceptionChangeInvalidatesAbiWhenConstantPoolIndexIsStable() throws IOException {
        assertAbiChanges(
                "Exceptions",
                "com/example/Api.class",
                "src/main/java/com/example/Api.java",
                """
                package com.example;

                import java.io.IOException;

                public class Api {
                    public void call() throws IOException { }
                }
                """,
                """
                package com.example;

                import java.sql.SQLException;

                public class Api {
                    public void call() throws SQLException { }
                }
                """);
    }

    @Test
    void annotationValueChangeInvalidatesAbiWhenConstantPoolIndexIsStable() throws IOException {
        assertAbiChanges(
                "RuntimeVisibleAnnotations",
                "com/example/Api.class",
                "src/main/java/com/example/Api.java",
                annotatedApi("before", false),
                annotatedApi("after!", false));
    }

    @Test
    void annotationDefaultChangeInvalidatesAbiWhenConstantPoolIndexIsStable() throws IOException {
        assertAbiChanges(
                "AnnotationDefault",
                "com/example/Marker.class",
                "src/main/java/com/example/Marker.java",
                """
                package com.example;

                public @interface Marker {
                    String value() default "before";
                }
                """,
                """
                package com.example;

                public @interface Marker {
                    String value() default "after!";
                }
                """);
    }

    @Test
    void parameterAnnotationValueChangeInvalidatesAbiWhenConstantPoolIndexIsStable() throws IOException {
        assertAbiChanges(
                "RuntimeVisibleParameterAnnotations",
                "com/example/Api.class",
                "src/main/java/com/example/Api.java",
                annotatedApi("before", true),
                annotatedApi("after!", true));
    }

    @Test
    void oneCharacterBinaryNamesRemainClassesOutsideDescriptorParsing() throws IOException {
        Path a = source("src/a/A.java", "public class A { public int[] values; }\n");
        Path i = source("src/i/I.java", "public class I { public Object[] values; }\n");
        Path packaged = source("src/p/p/A.java", "package p; public class A { public static class N {} }\n");

        Path aOutput = compileTo(a, tempDir.resolve("target/a"));
        Path iOutput = compileTo(i, tempDir.resolve("target/i"));
        Path packagedOutput = compileTo(packaged, tempDir.resolve("target/p"));

        assertEquals("A", reader.read(aOutput.resolve("A.class")).binaryName());
        assertEquals("I", reader.read(iOutput.resolve("I.class")).binaryName());
        assertEquals("p.A", reader.read(packagedOutput.resolve("p/A.class")).binaryName());
        assertEquals("p.A$N", reader.read(packagedOutput.resolve("p/A$N.class")).binaryName());
    }

    private void assertAbiChanges(
            String rawAttribute,
            String classFile,
            String sourcePath,
            String before,
            String after) throws IOException {
        Path source = source(sourcePath, before);
        Path firstClass = compileTo(source, tempDir.resolve("target/first")).resolve(classFile);
        ClassFileAbi first = reader.read(firstClass);
        byte[] firstBytes = Files.readAllBytes(firstClass);

        Files.writeString(source, after);
        Path secondClass = compileTo(source, tempDir.resolve("target/second")).resolve(classFile);
        ClassFileAbi second = reader.read(secondClass);
        byte[] secondBytes = Files.readAllBytes(secondClass);

        assertFalse(
                java.util.Arrays.equals(firstBytes, secondBytes),
                "fixture class bytes must differ");
        List<String> firstPayloads = rawAttributePayloads(firstBytes, rawAttribute);
        assertFalse(firstPayloads.isEmpty(), "fixture must contain " + rawAttribute);
        assertEquals(
                firstPayloads,
                rawAttributePayloads(secondBytes, rawAttribute),
                "fixture must preserve the raw constant-pool indexes in " + rawAttribute);
        assertNotEquals(first.abiHash(), second.abiHash());
        assertNotEquals(first.packagePrivateAbiHash(), second.packagePrivateAbiHash());
    }

    private static List<String> rawAttributePayloads(byte[] classBytes, String target) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            input.readUnsignedShort();
            ClassFileConstantPool constantPool = ClassFileConstantPool.read(input);
            input.skipNBytes(6);
            input.skipNBytes((long) input.readUnsignedShort() * 2L);
            List<String> payloads = new ArrayList<>();
            readMemberAttributes(input, constantPool, target, payloads);
            readMemberAttributes(input, constantPool, target, payloads);
            readAttributes(input, constantPool, target, payloads);
            return List.copyOf(payloads);
        }
    }

    private static void readMemberAttributes(
            DataInputStream input,
            ClassFileConstantPool constantPool,
            String target,
            List<String> payloads) throws IOException {
        int members = input.readUnsignedShort();
        for (int index = 0; index < members; index++) {
            input.skipNBytes(6);
            readAttributes(input, constantPool, target, payloads);
        }
    }

    private static void readAttributes(
            DataInputStream input,
            ClassFileConstantPool constantPool,
            String target,
            List<String> payloads) throws IOException {
        int attributes = input.readUnsignedShort();
        for (int index = 0; index < attributes; index++) {
            String name = constantPool.utf8(input.readUnsignedShort());
            int length = input.readInt();
            byte[] payload = input.readNBytes(length);
            assertEquals(length, payload.length);
            if (target.equals(name)) {
                payloads.add(HexFormat.of().formatHex(payload));
            }
        }
    }

    private static String annotatedApi(String value, boolean parameter) {
        String target = parameter
                ? "public void call(@Marker(\"" + value + "\") String input) { }"
                : "@Marker(\"" + value + "\") public void call() { }";
        return """
                package com.example;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class Api {
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Marker {
                        String value();
                    }

                    %s
                }
                """.formatted(target);
    }

    private Path source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private Path compileTo(Path source, Path output) {
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                output);
        return output;
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "javac.exe" : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
