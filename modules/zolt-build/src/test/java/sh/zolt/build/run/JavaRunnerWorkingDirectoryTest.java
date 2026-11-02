package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.Classpath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaRunnerWorkingDirectoryTest {
    @TempDir Path root;

    @Test
    void classpathLaunchKeepsRelativeSelectedJavaWhileChangingApplicationDirectory() throws Exception {
        Path directory = applicationDirectory();
        Path classes = Path.of(RelativeIo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        JavaRunResult result = new JavaRunner().run(relativeJava(), new Classpath(List.of(classes)),
                RelativeIo.class.getName(), List.of(), List.of(), directory, ignored -> {});
        assertApplicationIo(directory, result);
    }

    @Test
    void jarLaunchKeepsRelativeSelectedJavaWhileChangingApplicationDirectory() throws Exception {
        Path directory = applicationDirectory();
        Path jar = root.resolve("application.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, RelativeIo.class.getName());
        String entry = RelativeIo.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
                var input = RelativeIo.class.getResourceAsStream("/" + entry)) {
            output.putNextEntry(new JarEntry(entry));
            input.transferTo(output);
            output.closeEntry();
        }
        JavaRunResult result = new JavaRunner().runJar(relativeJava(), jar, RelativeIo.class.getName(),
                List.of(), List.of(), directory, ignored -> {});
        assertApplicationIo(directory, result);
    }

    private Path applicationDirectory() throws Exception {
        Path directory = Files.createDirectories(root.resolve("project/member"));
        Files.writeString(directory.resolve("request.txt"), "member-input", StandardCharsets.UTF_8);
        return directory;
    }

    private static Path relativeJava() {
        String name = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path executable = Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath().normalize();
        Path relative = Path.of("").toAbsolutePath().normalize().relativize(executable);
        assertFalse(relative.isAbsolute());
        assertTrue(Files.isExecutable(relative), "selected relative tool must exist in the caller directory");
        return relative;
    }

    private static void assertApplicationIo(Path directory, JavaRunResult result) throws Exception {
        assertEquals("member-input", Files.readString(directory.resolve("response.txt")));
        assertEquals("member-input", result.output().strip());
    }

    public static final class RelativeIo {
        public static void main(String[] arguments) throws Exception {
            String value = Files.readString(Path.of("request.txt"));
            Files.writeString(Path.of("response.txt"), value);
            System.out.println(value);
        }
    }
}
