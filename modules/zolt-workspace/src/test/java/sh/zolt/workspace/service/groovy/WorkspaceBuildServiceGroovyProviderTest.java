package sh.zolt.workspace.service.groovy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.lang.GroovyObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import sh.zolt.build.JavacException;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildServiceDependencyVisibilityTestSupport;

/** Proves Groovy provider ABI changes propagate through the workspace planner to Java consumers. */
final class WorkspaceBuildServiceGroovyProviderTest
        extends WorkspaceBuildServiceDependencyVisibilityTestSupport {
    private static final String GROOVY_VERSION = "4.0.22";

    @Test
    void groovyAbiChangeRecompilesJavaConsumerLikeCleanBuild() throws Exception {
        addPrebuiltJarArtifact("org.apache.groovy", "groovy", GROOVY_VERSION, groovyJar());
        workspace("""
                [workspace]
                name = "groovy-provider"

                [workspace.members]
                include = ["modules/groovy-api", "apps/consumer"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        Path cacheRoot = tempDir.resolve("cache");
        member("modules/groovy-api", "groovy-api", """

                [dependencies.api]
                "org.apache.groovy:groovy" = "4.0.22"
                """);
        source("modules/groovy-api/src/main/java/com/acme/api/GroovyApi.groovy", """
                package com.acme.api

                public final class GroovyApi {
                    static String value() {
                        "before"
                    }
                }
                """);
        Path provider = tempDir.resolve("modules/groovy-api/src/main/java/com/acme/api/GroovyApi.groovy");
        member("apps/consumer", "consumer", """

                [dependencies]
                "com.acme:groovy-api" = { workspace = true }
                """);
        source("apps/consumer/src/main/java/com/acme/consumer/Consumer.java", """
                package com.acme.consumer;

                import com.acme.api.GroovyApi;

                public final class Consumer {
                    public static String call() {
                        return GroovyApi.value();
                    }
                }
                """);

        WorkspaceBuildResult first = service.build(tempDir.resolve("apps/consumer"), cacheRoot, false);
        WorkspaceBuildResult warm = service.build(tempDir.resolve("apps/consumer"), cacheRoot, false);

        assertEquals(2, first.mainCompilationExecutedCount());
        assertEquals(2, warm.mainCompilationSkippedCount());
        assertTrue(Files.isRegularFile(tempDir.resolve(
                "modules/groovy-api/target/classes/com/acme/api/GroovyApi.class")));
        assertTrue(Files.isRegularFile(tempDir.resolve(
                "apps/consumer/target/classes/com/acme/consumer/Consumer.class")));

        Files.writeString(provider, """
                package com.acme.api

                public final class GroovyApi {
                    static int value() {
                        7
                    }
                }
                """);

        JavacException incremental = assertThrows(
                JavacException.class,
                () -> service.build(tempDir.resolve("apps/consumer"), cacheRoot, false));

        deleteTree(tempDir.resolve("modules/groovy-api/target"));
        deleteTree(tempDir.resolve("apps/consumer/target"));
        Files.deleteIfExists(tempDir.resolve(".zolt/workspace-state-v1"));
        JavacException clean = assertThrows(
                JavacException.class,
                () -> service.build(tempDir.resolve("apps/consumer"), cacheRoot, false));

        assertTypeMismatch(incremental);
        assertTypeMismatch(clean);
    }

    private static void assertTypeMismatch(JavacException exception) {
        assertTrue(exception.getMessage().contains("int cannot be converted to String"),
                exception.getMessage());
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

    private static Path groovyJar() throws URISyntaxException {
        return Path.of(GroovyObject.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }
}
