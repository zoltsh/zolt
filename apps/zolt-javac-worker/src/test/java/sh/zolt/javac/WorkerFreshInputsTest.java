package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkerFreshInputsTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistentWorkerReadsRetargetedSourceAndDirectoryLinks(boolean directoryLink) throws Exception {
        Path first = source("first", "before");
        Path second = source("second", "next!!");
        Path alias = tempDir.resolve(directoryLink ? "sources" : "Main.java");
        Files.createSymbolicLink(alias, directoryLink ? first.getParent() : first);
        Path source = directoryLink ? alias.resolve("Main.java") : alias;
        Path output = Files.createDirectory(tempDir.resolve("classes"));
        List<String> arguments = List.of("-proc:none", "-d", output.toString(), source.toString());

        compile(arguments);
        assertEquals("before", value(output));
        Files.delete(alias);
        Files.createSymbolicLink(alias, directoryLink ? second.getParent() : second);
        Files.delete(output.resolve("Main.class"));
        compile(arguments);

        assertEquals("next!!", value(output), "a fresh compile must follow the current source target");
    }

    @Test
    void persistentWorkerReadsAReplacedJarAtTheSameClasspathPath() throws Exception {
        Path library = tempDir.resolve("library.jar");
        library(library, "before");
        Path source = tempDir.resolve("Main.java");
        Path output = Files.createDirectory(tempDir.resolve("classes"));
        Files.writeString(source, "public class Main { public String value() { return Library.before(); } }");
        List<String> arguments = List.of("-proc:none", "-classpath", library.toString(),
                "-d", output.toString(), source.toString());
        compile(arguments);

        library(library, "after");
        Files.writeString(source, "public class Main { public String value() { return Library.after(); } }");
        Files.delete(output.resolve("Main.class"));
        compile(arguments);
    }

    private Path source(String directory, String value) throws Exception {
        Path source = Files.createDirectory(tempDir.resolve(directory)).resolve("Main.java");
        Files.writeString(source, "public class Main { public static String value() { return \"" + value + "\"; } }");
        return source;
    }

    private void library(Path jar, String method) throws Exception {
        Path work = Files.createDirectory(tempDir.resolve(method));
        Path source = work.resolve("Library.java");
        Files.writeString(source, "public class Library { public static String " + method + "() { return \"" + method + "\"; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", work.toString(), source.toString()));
        Path staged = tempDir.resolve(method + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(staged))) {
            output.putNextEntry(new JarEntry("Library.class"));
            output.write(Files.readAllBytes(work.resolve("Library.class")));
            output.closeEntry();
        }
        Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void compile(List<String> arguments) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WorkerCompile.run(WorkerCompileProtocol.KIND_COMPILE, arguments, new DataOutputStream(bytes));
        try (DataInputStream response = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            int exit = response.readInt();
            String diagnostics = new String(response.readNBytes(response.readInt()), StandardCharsets.UTF_8);
            assertEquals(0, exit, diagnostics);
            assertEquals(0, response.readInt(), "ordinary compile must have no attribution section");
        }
    }

    private static String value(Path output) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {output.toUri().toURL()}, null)) {
            return (String) loader.loadClass("Main").getMethod("value").invoke(null);
        }
    }
}
