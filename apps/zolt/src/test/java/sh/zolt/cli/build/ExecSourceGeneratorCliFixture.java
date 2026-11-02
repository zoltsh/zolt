package sh.zolt.cli.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.cli.CliTestRepository;

/** Publishes a tiny pinned JVM tool that copies one authored template into its owned output. */
public final class ExecSourceGeneratorCliFixture {
    public static final String VERSION = "1.0.0";
    public static final String COORDINATE = "com.example:kotlin-source-generator";
    public static final String MAIN_CLASS = "com.example.tool.KotlinSourceGenerator";

    private ExecSourceGeneratorCliFixture() {
    }

    public static void publish(CliTestRepository repository, Path workDirectory) throws IOException {
        repository.addArtifact(
                "com.example",
                "kotlin-source-generator",
                VERSION,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>kotlin-source-generator</artifactId>
                  <version>1.0.0</version>
                </project>
                """,
                jar(workDirectory));
    }

    private static byte[] jar(Path workDirectory) throws IOException {
        Path source = workDirectory.resolve("src/com/example/tool/KotlinSourceGenerator.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.tool;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardCopyOption;

                public final class KotlinSourceGenerator {
                    public static void main(String[] args) throws Exception {
                        Path project = Path.of(System.getenv("ZOLT_PROJECT_ROOT"));
                        Path target = Path.of(System.getenv("ZOLT_OUTPUT_DIR")).resolve(args[1]);
                        Files.createDirectories(target.getParent());
                        Files.copy(project.resolve(args[0]), target, StandardCopyOption.REPLACE_EXISTING);
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
            output.putNextEntry(new JarEntry("com/example/tool/KotlinSourceGenerator.class"));
            output.write(Files.readAllBytes(
                    classes.resolve("com/example/tool/KotlinSourceGenerator.class")));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "javac.exe"
                : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
