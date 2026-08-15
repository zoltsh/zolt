package sh.zolt.cli.nativeimage;

import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class NativeGeneratedSourceTestFixture {
    private NativeGeneratedSourceTestFixture() {
    }

    static byte[] jar(Path workDirectory) throws IOException {
        Path source = workDirectory.resolve("src/com/example/tool/SourceGenerator.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.tool;

                public final class SourceGenerator {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Path generated = java.nio.file.Path.of(
                                System.getenv("ZOLT_OUTPUT_DIR"))
                                .resolve("com/example/generated/Generated.java");
                        java.nio.file.Files.createDirectories(generated.getParent());
                        java.nio.file.Files.writeString(
                                generated,
                                "package com.example.generated; public final class Generated {}\\n");
                    }
                }
                """);
        Path classes = workDirectory.resolve("classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                classes);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry("com/example/tool/SourceGenerator.class"));
            output.write(Files.readAllBytes(
                    classes.resolve("com/example/tool/SourceGenerator.class")));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "javac.exe"
                : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
