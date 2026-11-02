package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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

final class ProcessorLoaderRefreshTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attributedRequestsObserveRebuiltProcessorDirectoriesAndReplacedJars(boolean useJar) throws Exception {
        Path classes = WorkerAttributionFixture.compileResourceProcessor(tempDir);
        Path processor = useJar ? jar(classes) : classes;
        Path source = Files.writeString(tempDir.resolve("Widget.java"), "public class Widget {}\n");
        Path first = Files.createDirectory(tempDir.resolve("first"));
        compile(processor, source, first);
        assertEquals("owner=Widget", Files.readString(first.resolve("gen/Widget.meta")));

        Path processorSource = tempDir.resolve("proc/GenResourceProc.java");
        Files.writeString(processorSource, Files.readString(processorSource).replace("owner=", "changed="));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", classes.toString(), processorSource.toString()));
        if (useJar) {
            jar(classes); // Replaces the same path; on Windows this also requires the old loader to be closed.
        }
        Path second = Files.createDirectory(tempDir.resolve("second"));
        compile(processor, source, second);

        assertEquals("changed=Widget", Files.readString(second.resolve("gen/Widget.meta")));
        if (useJar) {
            Files.delete(processor);
        }
    }

    @Test
    void failedProcessorDiscoveryClosesTheJarAndAllowsRepair() throws Exception {
        Path classes = WorkerAttributionFixture.compileResourceProcessor(tempDir);
        Path service = classes.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.writeString(service, "proc.MissingProcessor\n");
        Path processor = jar(classes);
        Path source = Files.writeString(tempDir.resolve("Widget.java"), "public class Widget {}\n");
        Path output = Files.createDirectory(tempDir.resolve("classes"));
        assertNotEquals(0, result(processor, source, output).exitCode());

        Files.writeString(service, "proc.GenResourceProc\n");
        jar(classes);
        compile(processor, source, output);

        assertEquals("owner=Widget", Files.readString(output.resolve("gen/Widget.meta")));
        Files.delete(processor);
    }

    private Path jar(Path classes) throws Exception {
        Path temporary = tempDir.resolve("processor-next.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporary));
                var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        Path target = tempDir.resolve("processor.jar");
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static void compile(Path processor, Path source, Path output) throws Exception {
        var response = result(processor, source, output);
        assertEquals(0, response.exitCode(), response.diagnostics());
    }

    private static WorkerAttributionFixture.Attribution result(Path processor, Path source, Path output) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WorkerCompile.run(WorkerCompileProtocol.KIND_COMPILE_ATTRIBUTED,
                List.of("-d", output.toString(), "-processorpath", processor.toString(), source.toString()),
                new DataOutputStream(bytes));
        return WorkerAttributionFixture.parse(bytes.toByteArray());
    }
}
