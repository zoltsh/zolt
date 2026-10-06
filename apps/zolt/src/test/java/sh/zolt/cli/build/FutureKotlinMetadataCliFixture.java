package sh.zolt.cli.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import sh.zolt.cli.CliTestRepository;

/** Publishes a Kotlin API whose deliberately future metadata version requires an explicit bypass. */
public final class FutureKotlinMetadataCliFixture {
    public static final String GROUP = "com.example";
    public static final String ARTIFACT = "future-kotlin-api";
    public static final String VERSION = "1.0.0";

    private FutureKotlinMetadataCliFixture() {}

    public static void publish(CliTestRepository repository, Path workDirectory) throws Exception {
        Path jar = providerJar(workDirectory);
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

    private static Path providerJar(Path workDirectory) throws Exception {
        Path source = workDirectory.resolve("src/future/FutureApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package future

                object FutureApi {
                    @JvmStatic
                    fun value(): String = "future-metadata"
                }
                """);
        Path classes = workDirectory.resolve("classes");
        Files.createDirectories(classes);
        compile(source, classes);
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

    private static void compile(Path source, Path classes) throws Exception {
        Class<?> compilerType = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        Object compiler = compilerType.getConstructor().newInstance();
        Method exec = compilerType.getMethod("exec", PrintStream.class, String[].class);
        Path stdlib = codeSource("kotlin.Unit");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Object exitCode;
        try (PrintStream output = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            String[] arguments = {
                "-no-stdlib",
                "-no-reflect",
                "-classpath", stdlib.toString(),
                "-jvm-target", Integer.toString(Runtime.version().feature()),
                "-Xmetadata-version=99.0.0",
                "-module-name", "future_metadata",
                "-d", classes.toString(),
                source.toString()
            };
            exitCode = exec.invoke(compiler, output, (Object) arguments);
        }
        if (!"OK".equals(exitCode.toString())) {
            throw new IllegalStateException(
                    "Could not compile the future Kotlin metadata fixture: "
                            + diagnostics.toString(StandardCharsets.UTF_8).strip());
        }
    }

    private static Path codeSource(String className) throws ClassNotFoundException, URISyntaxException {
        return Path.of(Class.forName(className)
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }
}
