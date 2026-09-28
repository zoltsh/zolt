package sh.zolt.workspace.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.JavacException;
import sh.zolt.build.incremental.IncrementalCompileSummaryReader;
import sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestService;

/** Pending main outputs invalidate consumers that see them only on their test classpath. */
final class WorkspacePendingTestDependencyRebuildTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void buildOnce() throws IOException {
        write("zolt.toml", """
                [workspace]
                name = "pending-test-dependency"

                [workspace.members]
                include = ["modules/provider", "modules/fixture", "apps/consumer"]
                """);
        member("modules/provider", "provider", "");
        writeProvider("String", "\"before\"");
        member("modules/fixture", "fixture", """

                [dependencies]
                "com.acme:provider" = { workspace = true }
                """);
        write("modules/fixture/src/main/java/com/acme/fixture/Fixture.java", """
                package com.acme.fixture;

                public final class Fixture {
                }
                """);
        member("apps/consumer", "consumer", """

                [dependencies.test]
                "com.acme:fixture" = { workspace = true }
                """);
        write("apps/consumer/src/main/java/com/acme/consumer/Consumer.java", """
                package com.acme.consumer;

                public final class Consumer {
                }
                """);
        write("apps/consumer/src/test/java/com/acme/consumer/ConsumerTest.java", """
                package com.acme.consumer;

                import com.acme.provider.Provider;

                public final class ConsumerTest {
                    public static String value() {
                        return Provider.value();
                    }
                }
                """);
        writeTestLock();
        compile();
    }

    @Test
    void pendingTransitiveTestDependencyFailsIncrementalAndCleanCompilationsEqually()
            throws IOException {
        String fixtureAbi = fixtureAbi();
        writeProvider("int", "1");

        PendingCompile incrementalInputs = pendingCompile();
        WorkspaceBuildResult.MemberBuildResult consumer = incrementalInputs.build().members().stream()
                .filter(member -> member.member().equals("apps/consumer"))
                .findFirst()
                .orElseThrow();

        assertEquals(fixtureAbi, fixtureAbi(), "the intermediate fixture ABI is unchanged");
        assertEquals(2, incrementalInputs.build().executionMetrics().memberPipelineInvocations());
        assertTrue(consumer.result().mainCompilationSkipped(),
                "a test-only dependency must not admit the consumer's main pipeline");
        assertTrue(incrementalInputs.build()
                .membersRequiringTestCompile()
                .contains("apps/consumer"));
        JavacException incremental = assertThrows(
                JavacException.class,
                () -> service.compileTests(
                        incrementalInputs.plan(),
                        incrementalInputs.build()));

        deleteTree(tempDir.resolve("modules/provider/target"));
        deleteTree(tempDir.resolve("modules/fixture/target"));
        deleteTree(tempDir.resolve("apps/consumer/target"));
        Files.deleteIfExists(tempDir.resolve(".zolt/workspace-state-v1"));
        PendingCompile cleanInputs = pendingCompile();
        JavacException clean = assertThrows(
                JavacException.class,
                () -> service.compileTests(cleanInputs.plan(), cleanInputs.build()));

        assertTrue(incremental.getMessage().contains("ConsumerTest.java"));
        assertTrue(clean.getMessage().contains("ConsumerTest.java"));
        assertTrue(incremental.getMessage().contains("cannot be converted to String"));
        assertTrue(clean.getMessage().contains("cannot be converted to String"));
    }

    private void compile() {
        PendingCompile pending = pendingCompile();
        service.compileTests(pending.plan(), pending.build());
    }

    private PendingCompile pendingCompile() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        return new PendingCompile(plan, service.buildTestCompileInputs(plan, cacheRoot));
    }

    private String fixtureAbi() {
        return new IncrementalCompileSummaryReader()
                .readMain(tempDir.resolve("modules/fixture/target/classes"))
                .orElseThrow()
                .compileAbiDigest();
    }

    private void writeProvider(String type, String value) throws IOException {
        write("modules/provider/src/main/java/com/acme/provider/Provider.java", """
                package com.acme.provider;

                public final class Provider {
                    public static %s value() {
                        return %s;
                    }
                }
                """.formatted(type, value));
    }

    private void writeTestLock() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        createFakeConsoleJar(
                tempDir,
                cacheRoot.resolve(
                        "org/junit/platform/junit-platform-console/1.11.4/"
                                + "junit-platform-console-1.11.4.jar"));
        WorkspaceContentAddressedLockTestSupport.write(
                tempDir.resolve("zolt.lock"),
                cacheRoot,
                """
                        version = 7

                        [[dependencyRoot]]
                        member = "modules/fixture"
                        id = "com.acme:provider"
                        version = "0.1.0"
                        lane = "implementation"
                        resolvedScope = "compile"

                        [[dependencyRoot]]
                        member = "apps/consumer"
                        id = "com.acme:fixture"
                        version = "0.1.0"
                        lane = "test"
                        resolvedScope = "test"

                        [[package]]
                        id = "com.acme:provider"
                        version = "0.1.0"
                        source = "workspace"
                        scope = "compile"
                        direct = true
                        workspace = "modules/provider"
                        workspaceOutput = "target/classes"
                        members = ["modules/fixture"]
                        dependencies = []

                        [[package]]
                        id = "com.acme:fixture"
                        version = "0.1.0"
                        source = "workspace"
                        scope = "test"
                        direct = true
                        workspace = "modules/fixture"
                        workspaceOutput = "target/classes"
                        members = ["apps/consumer"]
                        dependencies = ["com.acme:provider:0.1.0:jar:compile"]

                        [[package]]
                        id = "org.junit.platform:junit-platform-console"
                        version = "1.11.4"
                        source = "maven-central"
                        scope = "test"
                        direct = false
                        jar = "org/junit/platform/junit-platform-console/1.11.4/junit-platform-console-1.11.4.jar"
                        members = ["modules/provider", "modules/fixture", "apps/consumer"]
                        dependencies = []
                        """);
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

    private record PendingCompile(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult build) {
    }
}
