package sh.zolt.cli.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import sh.zolt.cli.CliTestRepository;

/** Publishes a real annotation processor that generates one Java type. */
public final class KaptProcessorCliFixture {
    public static final String GROUP = "com.example";
    public static final String ARTIFACT = "test-message-processor";
    public static final String VERSION = "1.0.0";

    private KaptProcessorCliFixture() {}

    public static void publish(CliTestRepository repository, Path workDirectory) throws IOException {
        Path jar = processorJar(workDirectory);
        repository.addArtifact(
                GROUP,
                ARTIFACT,
                VERSION,
                """
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </project>
                        """.formatted(GROUP, ARTIFACT, VERSION),
                Files.readAllBytes(jar));
    }

    private static Path processorJar(Path workDirectory) throws IOException {
        Path source = workDirectory.resolve("src/com/example/processor/TestMessageProcessor.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.processor;

                import java.io.IOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import javax.tools.JavaFileObject;

                @SupportedAnnotationTypes("*")
                public final class TestMessageProcessor extends AbstractProcessor {
                    private boolean generated;

                    @Override
                    public SourceVersion getSupportedSourceVersion() {
                        return SourceVersion.latestSupported();
                    }

                    @Override
                    public boolean process(
                            Set<? extends TypeElement> annotations,
                            RoundEnvironment roundEnvironment) {
                        if (generated || roundEnvironment.processingOver()) {
                            return false;
                        }
                        try {
                            String message = processingEnv.getOptions()
                                    .getOrDefault("zolt.message", "generated-test");
                            JavaFileObject source = processingEnv.getFiler()
                                    .createSourceFile("com.example.GeneratedTestMessage");
                            try (Writer writer = source.openWriter()) {
                                writer.write("package com.example; "
                                        + "public final class GeneratedTestMessage { "
                                        + "public static String value() { return \\"" + message + "\\"; } "
                                        + "}");
                            }
                            generated = true;
                            return false;
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    }
                }
                """);
        Path classes = workDirectory.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("KAPT CLI fixture requires a full JDK with javac.");
        }
        int exitCode = compiler.run(
                null,
                null,
                null,
                "-proc:none",
                "-d",
                classes.toString(),
                source.toString());
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Could not compile the KAPT CLI fixture processor; javac exited " + exitCode + ".");
        }
        Path service = classes.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "com.example.processor.TestMessageProcessor\n");
        Path jar = workDirectory.resolve(ARTIFACT + "-" + VERSION + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(
                        classes.relativize(file).toString().replace(File.separatorChar, '/'));
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return jar;
    }
}
