package sh.zolt.build;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds the service-loaded KSP processor used by real-compiler integration tests. */
final class KspProcessorFixture {
    private static final String PROVIDER_SERVICE =
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider";

    private KspProcessorFixture() {
    }

    static Path processorJar(Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            addClass(jar, KspProcessorFixture.class);
            addClass(jar, Provider.class);
            addClass(jar, Processor.class);
            add(jar, PROVIDER_SERVICE, Provider.class.getName() + "\n");
        }
        return output.toAbsolutePath().normalize();
    }

    private static void addClass(JarOutputStream jar, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing compiled KSP fixture class " + resource);
            }
            jar.putNextEntry(new JarEntry(resource));
            input.transferTo(jar);
            jar.closeEntry();
        }
    }

    private static void add(JarOutputStream jar, String entry, String content) throws IOException {
        jar.putNextEntry(new JarEntry(entry));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    public static final class Provider implements SymbolProcessorProvider {
        @Override
        public SymbolProcessor create(SymbolProcessorEnvironment environment) {
            String message = environment.getOptions().get("fixture.message");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("KSP fixture message option was not forwarded.");
            }
            boolean failAfterKotlin = Boolean.parseBoolean(
                    environment.getOptions().getOrDefault("fixture.failAfterKotlin", "false"));
            return new Processor(environment.getCodeGenerator(), message, failAfterKotlin);
        }
    }

    private static final class Processor implements SymbolProcessor {
        private final CodeGenerator generator;
        private final String message;
        private final boolean failAfterKotlin;
        private boolean generated;

        private Processor(
                CodeGenerator generator,
                String message,
                boolean failAfterKotlin) {
            this.generator = generator;
            this.message = message;
            this.failAfterKotlin = failAfterKotlin;
        }

        @Override
        public List<KSAnnotated> process(Resolver resolver) {
            if (generated) {
                return List.of();
            }
            generated = true;
            Dependencies dependencies = new Dependencies(false);
            write(generator.createNewFile(
                    dependencies, "com.example", "GeneratedKspMessage", "kt"), """
                    package com.example

                    object GeneratedKspMessage {
                        @JvmStatic
                        fun value(): String = "%s"
                    }
                    """.formatted(stringLiteral(message)));
            if (failAfterKotlin) {
                throw new IllegalStateException("KSP fixture failed after a partial staged write.");
            }
            write(generator.createNewFile(
                    dependencies, "com.example", "GeneratedJavaMessage", "java"), """
                    package com.example;

                    public final class GeneratedJavaMessage {
                        private GeneratedJavaMessage() {}

                        public static String value() {
                            return GeneratedKspMessage.value();
                        }
                    }
                    """);
            write(generator.createNewFileByPath(
                    dependencies, "META-INF/ksp-fixture", "txt"), message + "-resource\n");
            return List.of();
        }

        private static String stringLiteral(String value) {
            return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
        }

        private static void write(OutputStream output, String content) {
            try (output) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
