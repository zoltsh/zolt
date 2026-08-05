package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavacBrokerIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void identityTracksTheCompilerTheWorkerArtifactAndTheChildFlags() throws Exception {
        Path firstJavac = javac("jdk-one");
        Path secondJavac = javac("jdk-two");
        Path worker = tempDir.resolve("zolt-javac-worker.jar");
        Files.writeString(worker, "worker-one");
        Path runtime = tempDir.resolve("runtime");

        Path first = JavacBrokerIdentity.of(firstJavac, worker).statePath(runtime);
        assertEquals(first, JavacBrokerIdentity.of(firstJavac, worker).statePath(runtime));
        assertNotEquals(first, JavacBrokerIdentity.of(secondJavac, worker).statePath(runtime));
        assertNotEquals(
                first,
                new JavacBrokerIdentity(firstJavac, worker, List.of("-Xmx2g")).statePath(runtime));

        Files.writeString(worker, "worker-two-with-a-different-size");
        assertNotEquals(
                first,
                JavacBrokerIdentity.of(firstJavac, worker).statePath(runtime),
                "a rebuilt worker artifact must not reuse the previous broker's children");
    }

    @Test
    void startCommandRunsTheWorkerArtifactWithTheJdkThatOwnsTheCompiler() throws Exception {
        Path javac = javac("jdk-one");
        Path worker = tempDir.resolve("zolt-javac-worker.jar");
        Files.writeString(worker, "worker");
        JavacBrokerIdentity identity = new JavacBrokerIdentity(javac, worker, List.of("-Xshare:auto"));

        List<String> command = identity.startCommand(tempDir.resolve("broker.state"));

        assertTrue(command.getFirst().endsWith("jdk-one/bin/java"), command.getFirst());
        assertTrue(command.contains("--broker"));
        assertEquals(List.of("-Xshare:auto"), List.of(command.get(1)));
        assertTrue(
                command.contains("--worker-jvm-arg"),
                "children must be started with the same flags the identity was keyed on");
    }

    @Test
    void anUnstartableBrokerLeavesTheCommandToItsOwnWorkers() throws Exception {
        Path worker = tempDir.resolve("zolt-javac-worker.jar");
        Files.writeString(worker, "worker");
        System.setProperty(
                "zolt.javac.worker.runtimeDirectory",
                tempDir.resolve("runtime").toString());
        try {
            Optional<JavacRunner.ProcessResult> result = JavacBrokerClient.compile(
                    tempDir.resolve("missing-jdk/bin/javac"),
                    worker,
                    JavacWorkerWire.KIND_COMPILE,
                    List.of("-version"));

            assertTrue(result.isEmpty(), "a broker that cannot start must never fail the build");
        } finally {
            System.clearProperty("zolt.javac.worker.runtimeDirectory");
        }
    }

    private Path javac(String jdk) throws Exception {
        Path javac = tempDir.resolve(jdk).resolve("bin").resolve("javac");
        Files.createDirectories(javac.getParent());
        Files.writeString(javac, jdk);
        return javac;
    }
}
