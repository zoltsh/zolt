package sh.zolt.workspace.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.JavacException;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;

/** Pending workspace rebuilds reach compile-visible and processor-dependent members. */
final class WorkspacePendingRebuildPropagationTest {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void buildOnce() throws IOException {
        write("zolt.toml", """
                [workspace]
                name = "pending-rebuilds"

                [workspace.members]
                include = [
                    "modules/root",
                    "modules/middle",
                    "apps/downstream",
                    "modules/processor-consumer",
                    "apps/leaf"
                ]
                """);
        member("modules/root", "root", "");
        write("modules/root/src/main/java/com/acme/root/Root.java", rootSource("first"));
        member("modules/middle", "middle", """

                [dependencies.api]
                "com.acme:root" = { workspace = true }
                """);
        write("modules/middle/src/main/java/com/acme/middle/Middle.java", """
                package com.acme.middle;

                public final class Middle {
                }
                """);
        member("apps/downstream", "downstream", """

                [dependencies]
                "com.acme:middle" = { workspace = true }
                """);
        write("apps/downstream/src/main/java/com/acme/downstream/Downstream.java", """
                package com.acme.downstream;

                import com.acme.root.Root;

                public final class Downstream {
                    public static String value() {
                        return Root.value();
                    }
                }
                """);
        member("modules/processor-consumer", "processor-consumer", """

                [dependencies.processor]
                "com.acme:middle" = { workspace = true }
                """);
        write("modules/processor-consumer/src/main/java/com/acme/processor/Consumer.java", """
                package com.acme.processor;

                public final class Consumer {
                }
                """);
        member("apps/leaf", "leaf", """

                [dependencies]
                "com.acme:processor-consumer" = { workspace = true }
                """);
        write("apps/leaf/src/main/java/com/acme/leaf/Leaf.java", """
                package com.acme.leaf;

                import com.acme.processor.Consumer;

                public final class Leaf {
                    private final Consumer consumer = new Consumer();
                }
                """);
        service.build(tempDir, tempDir.resolve("cache"), false);
    }

    @Test
    void reachesFixedPointAcrossTransitiveCompileAndProcessorEdges() throws IOException {
        write("modules/root/src/main/java/com/acme/root/Root.java", rootSource("second"));

        WorkspaceBuildResult result = service.build(tempDir, tempDir.resolve("cache"), false);

        assertEquals(5, result.executionMetrics().memberPipelineInvocations());
        assertTrue(result.members().stream()
                .filter(member -> "modules/root".equals(member.member()))
                .noneMatch(member -> member.result().mainCompilationSkipped()));
    }

    @Test
    void transitiveExportedProviderChangeFailsIncrementalAndCleanBuildsEqually()
            throws IOException {
        write("modules/root/src/main/java/com/acme/root/Root.java", """
                package com.acme.root;

                public final class Root {
                    public static int value() {
                        return 1;
                    }
                }
                """);

        JavacException incremental = assertThrows(
                JavacException.class,
                () -> service.build(tempDir, tempDir.resolve("cache"), false));

        for (String member : List.of(
                "modules/root",
                "modules/middle",
                "apps/downstream",
                "modules/processor-consumer",
                "apps/leaf")) {
            deleteTree(tempDir.resolve(member).resolve("target"));
        }
        Files.deleteIfExists(tempDir.resolve(".zolt/workspace-state-v1"));
        JavacException clean = assertThrows(
                JavacException.class,
                () -> service.build(tempDir, tempDir.resolve("cache"), false));

        assertTrue(incremental.getMessage().contains("Downstream.java"));
        assertTrue(clean.getMessage().contains("Downstream.java"));
        assertTrue(incremental.getMessage().contains("cannot be converted to String"));
        assertTrue(clean.getMessage().contains("cannot be converted to String"));
    }

    private void member(String path, String name, String extra) throws IOException {
        write(path + "/zolt.toml", """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = %s
                %s""".formatted(name, Runtime.version().feature(), extra));
    }

    private void write(String relative, String content) throws IOException {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String rootSource(String value) {
        return """
                package com.acme.root;

                public final class Root {
                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(value);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
