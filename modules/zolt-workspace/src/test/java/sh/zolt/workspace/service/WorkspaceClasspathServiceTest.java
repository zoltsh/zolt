package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.WorkspaceConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceClasspathServiceTest {
    private final WorkspaceClasspathService service = new WorkspaceClasspathService();
    private final ZoltLockfileReader lockfileReader = new ZoltLockfileReader();

    @TempDir
    private Path tempDir;

    @Test
    void filtersWorkspaceOutputsToMemberDependencyClosure() throws IOException {
        Workspace workspace = workspace(
                List.of("apps/api", "apps/worker", "modules/core", "modules/extra"),
                List.of(
                        new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core"),
                        new WorkspaceProjectEdge("apps/worker", "modules/extra", "compile", "com.acme:extra")));
        Path coreOutput = tempDir.resolve("modules/core/target/classes").normalize();
        Path extraOutput = tempDir.resolve("modules/extra/target/classes").normalize();
        Path coreHelperJar = tempDir.resolve("cache/org/example/core-helper/1.0.0/core-helper-1.0.0.jar");
        Path coreApiJar = tempDir.resolve("cache/org/example/core-api/1.0.0/core-api-1.0.0.jar");
        Path workerHelperJar = tempDir.resolve("cache/org/example/worker-helper/1.0.0/worker-helper-1.0.0.jar");
        Path legacyJar = tempDir.resolve("cache/org/example/legacy/1.0.0/legacy-1.0.0.jar");
        createEmptyFile(coreHelperJar);
        createEmptyFile(coreApiJar);
        createEmptyFile(workerHelperJar);
        createEmptyFile(legacyJar);
        ZoltLockfile lockfile = lockfileReader.read("""
                version = 5

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "com.acme:extra"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/extra"
                workspaceOutput = "target/classes"
                members = ["apps/worker"]
                dependencies = []

                [[package]]
                id = "org.example:core-helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/core-helper/1.0.0/core-helper-1.0.0.jar"
                members = ["modules/core"]
                dependencies = []

                [[package]]
                id = "org.example:core-api"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/core-api/1.0.0/core-api-1.0.0.jar"
                members = ["modules/core"]
                exportedBy = ["modules/core"]
                dependencies = []

                [[package]]
                id = "org.example:worker-helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/worker-helper/1.0.0/worker-helper-1.0.0.jar"
                members = ["apps/worker"]
                dependencies = []

                [[package]]
                id = "org.example:legacy"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/legacy/1.0.0/legacy-1.0.0.jar"
                dependencies = []
                """);

        ClasspathSet apiClasspaths = service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/api");
        ClasspathSet workerClasspaths = service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/worker");
        Map<String, ClasspathSet> classpathsByMember = service.classpathsForMembers(
                workspace,
                lockfile,
                tempDir.resolve("cache"),
                List.of("apps/api", "apps/worker"));
        Map<String, ClasspathSet> selectedTestClasspathsByMember = service.classpathsForMembers(
                workspace,
                lockfile,
                tempDir.resolve("cache"),
                List.of("modules/core", "apps/api"),
                Set.of("apps/api"));

        assertEquals(List.of("apps/api", "apps/worker"), List.copyOf(classpathsByMember.keySet()));
        assertEquals(apiClasspaths, classpathsByMember.get("apps/api"));
        assertEquals(workerClasspaths, classpathsByMember.get("apps/worker"));
        assertTrue(selectedTestClasspathsByMember.get("modules/core").compile().entries().contains(coreHelperJar));
        assertTrue(selectedTestClasspathsByMember.get("modules/core").compile().entries().contains(coreApiJar));
        assertTrue(selectedTestClasspathsByMember.get("modules/core").runtime().entries().isEmpty());
        assertTrue(selectedTestClasspathsByMember.get("modules/core").test().entries().isEmpty());
        assertEquals(apiClasspaths, selectedTestClasspathsByMember.get("apps/api"));
        assertTrue(apiClasspaths.compile().entries().contains(coreOutput));
        assertFalse(apiClasspaths.compile().entries().contains(extraOutput));
        assertFalse(apiClasspaths.compile().entries().contains(coreHelperJar));
        assertTrue(apiClasspaths.compile().entries().contains(coreApiJar));
        assertFalse(apiClasspaths.compile().entries().contains(workerHelperJar));
        assertTrue(apiClasspaths.compile().entries().contains(legacyJar));
        assertTrue(apiClasspaths.runtime().entries().contains(coreHelperJar));
        assertTrue(apiClasspaths.runtime().entries().contains(coreApiJar));
        assertTrue(workerClasspaths.compile().entries().contains(extraOutput));
        assertFalse(workerClasspaths.compile().entries().contains(coreOutput));
        assertFalse(workerClasspaths.compile().entries().contains(coreHelperJar));
        assertFalse(workerClasspaths.compile().entries().contains(coreApiJar));
        assertTrue(workerClasspaths.compile().entries().contains(workerHelperJar));
        assertTrue(workerClasspaths.compile().entries().contains(legacyJar));
    }

    @Test
    void routesWorkspaceProcessorClosureOnlyOntoProcessorLanes() throws IOException {
        Workspace workspace = workspace(
                List.of("apps/api", "modules/helper", "modules/processor", "modules/test-processor"),
                List.of(
                        new WorkspaceProjectEdge("apps/api", "modules/processor", "processor", "com.acme:processor"),
                        new WorkspaceProjectEdge("apps/api", "modules/test-processor", "test-processor", "com.acme:test-processor"),
                        new WorkspaceProjectEdge("modules/processor", "modules/helper", "compile", "com.acme:helper")));
        Path processorOutput = tempDir.resolve("modules/processor/target/classes").normalize();
        Path helperOutput = tempDir.resolve("modules/helper/target/classes").normalize();
        Path testProcessorOutput = tempDir.resolve("modules/test-processor/target/classes").normalize();
        Path externalApiProcessorJar =
                tempDir.resolve("cache/org/example/api-processor/1.0.0/api-processor-1.0.0.jar");
        Path externalProcessorHelperJar =
                tempDir.resolve("cache/org/example/processor-helper/1.0.0/processor-helper-1.0.0.jar");
        Path externalIgnoredJar =
                tempDir.resolve("cache/org/example/ignored/1.0.0/ignored-1.0.0.jar");
        createEmptyFile(externalApiProcessorJar);
        createEmptyFile(externalProcessorHelperJar);
        createEmptyFile(externalIgnoredJar);
        ZoltLockfile lockfile = lockfileReader.read("""
                version = 5

                [[package]]
                id = "com.acme:processor"
                version = "0.1.0"
                source = "workspace"
                scope = "processor"
                direct = true
                workspace = "modules/processor"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "com.acme:helper"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/helper"
                workspaceOutput = "target/classes"
                members = ["modules/processor"]
                dependencies = []

                [[package]]
                id = "com.acme:test-processor"
                version = "0.1.0"
                source = "workspace"
                scope = "test-processor"
                direct = true
                workspace = "modules/test-processor"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "org.example:api-processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "org/example/api-processor/1.0.0/api-processor-1.0.0.jar"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "org.example:processor-helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/example/processor-helper/1.0.0/processor-helper-1.0.0.jar"
                members = ["modules/processor"]
                dependencies = []

                [[package]]
                id = "org.example:ignored"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/example/ignored/1.0.0/ignored-1.0.0.jar"
                members = ["modules/unrelated"]
                dependencies = []
                """);

        ClasspathSet apiClasspaths = service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/api");

        assertTrue(apiClasspaths.processor().entries().contains(externalApiProcessorJar));
        assertTrue(apiClasspaths.processor().entries().contains(processorOutput));
        assertTrue(apiClasspaths.processor().entries().contains(helperOutput));
        assertTrue(apiClasspaths.processor().entries().contains(externalProcessorHelperJar));
        assertTrue(apiClasspaths.testProcessor().entries().contains(testProcessorOutput));
        assertFalse(apiClasspaths.processor().entries().contains(testProcessorOutput));
        assertFalse(apiClasspaths.testProcessor().entries().contains(processorOutput));
        assertFalse(apiClasspaths.compile().entries().contains(processorOutput));
        assertFalse(apiClasspaths.compile().entries().contains(helperOutput));
        assertFalse(apiClasspaths.compile().entries().contains(externalProcessorHelperJar));
        assertFalse(apiClasspaths.runtime().entries().contains(processorOutput));
        assertFalse(apiClasspaths.runtime().entries().contains(helperOutput));
        assertFalse(apiClasspaths.runtime().entries().contains(externalProcessorHelperJar));
        assertFalse(apiClasspaths.test().entries().contains(processorOutput));
        assertFalse(apiClasspaths.test().entries().contains(helperOutput));
        assertFalse(apiClasspaths.test().entries().contains(externalProcessorHelperJar));
        assertFalse(apiClasspaths.processor().entries().contains(externalIgnoredJar));
        assertFalse(apiClasspaths.testProcessor().entries().contains(externalIgnoredJar));
    }

    @Test
    void routesEachMembersOwnClassifierVariantOntoItsClasspath() throws IOException {
        Workspace workspace = workspace(List.of("apps/api", "apps/worker"), List.of());
        Path linuxJar = tempDir.resolve(
                "cache/io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar");
        Path osxJar = tempDir.resolve(
                "cache/io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-osx-aarch_64.jar");
        // Distinguishable bytes per variant, with matching hashes so the integrity verifier participates.
        writeFile(linuxJar, "linux-native-bytes");
        writeFile(osxJar, "osx-native-bytes");
        ZoltLockfile lockfile = lockfileReader.read(("""
                version = 5

                [[package]]
                id = "io.netty:netty-transport-native-epoll"
                version = "4.1.100.Final"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar"
                jarSha256 = "LINUX_SHA"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "io.netty:netty-transport-native-epoll"
                version = "4.1.100.Final"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-osx-aarch_64.jar"
                jarSha256 = "OSX_SHA"
                members = ["apps/worker"]
                dependencies = []
                """)
                .replace("LINUX_SHA", sha256("linux-native-bytes"))
                .replace("OSX_SHA", sha256("osx-native-bytes")));

        ClasspathSet apiClasspaths = service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/api");
        ClasspathSet workerClasspaths = service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/worker");

        // Each member's runtime classpath receives exactly the classified artifact ITS declaration requires,
        // and never the sibling variant's bytes.
        assertTrue(apiClasspaths.runtime().entries().contains(linuxJar));
        assertFalse(apiClasspaths.runtime().entries().contains(osxJar));
        assertTrue(workerClasspaths.runtime().entries().contains(osxJar));
        assertFalse(workerClasspaths.runtime().entries().contains(linuxJar));
    }

    @Test
    void downstreamCompileClasspathInheritsClassifiedApiVariant() throws IOException {
        Workspace workspace = workspace(
                List.of("modules/core", "apps/api"),
                List.of(new WorkspaceProjectEdge(
                        "apps/api", "modules/core", "compile", "com.acme:core")));
        Path linuxJar = tempDir.resolve(
                "cache/io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar");
        writeFile(linuxJar, "linux-api-bytes");
        ZoltLockfile lockfile = lockfileReader.read(("""
                version = 5

                [[package]]
                id = "io.netty:netty-transport-native-epoll"
                version = "4.1.100.Final"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar"
                jarSha256 = "LINUX_SHA"
                members = ["modules/core"]
                exportedBy = ["modules/core"]
                dependencies = []
                """).replace("LINUX_SHA", sha256("linux-api-bytes")));

        ClasspathSet classpaths =
                service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/api");

        assertTrue(classpaths.compile().entries().contains(linuxJar));
    }

    @Test
    void downstreamCompileClasspathWalksTheVariantQualifiedExportedApiClosure() throws IOException {
        Workspace workspace = workspace(
                List.of("modules/core", "apps/api"),
                List.of(new WorkspaceProjectEdge(
                        "apps/api", "modules/core", "compile", "com.acme:core")));
        Path apiJar = tempDir.resolve("cache/com/example/api-lib/1.0.0/api-lib-1.0.0.jar");
        Path linuxJar =
                tempDir.resolve("cache/com/example/fixture/1.0.0/fixture-1.0.0-linux-x86_64.jar");
        Path plainJar = tempDir.resolve("cache/com/example/fixture/1.0.0/fixture-1.0.0.jar");
        writeFile(apiJar, "api-lib-bytes");
        writeFile(linuxJar, "linux-transitive-bytes");
        writeFile(plainJar, "plain-transitive-bytes");
        ZoltLockfile lockfile = lockfileReader.read(("""
                version = 2

                [[package]]
                id = "com.example:api-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/api-lib/1.0.0/api-lib-1.0.0.jar"
                jarSha256 = "API_SHA"
                members = ["modules/core"]
                exportedBy = ["modules/core"]
                dependencies = ["com.example:fixture:1.0.0:jar|linux-x86_64"]

                [[package]]
                id = "com.example:fixture"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "com/example/fixture/1.0.0/fixture-1.0.0.jar"
                jarSha256 = "PLAIN_SHA"
                members = ["modules/core"]
                dependencies = []

                [[package]]
                id = "com.example:fixture"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "com/example/fixture/1.0.0/fixture-1.0.0-linux-x86_64.jar"
                jarSha256 = "LINUX_SHA"
                members = ["modules/core"]
                dependencies = []
                """)
                .replace("API_SHA", sha256("api-lib-bytes"))
                .replace("PLAIN_SHA", sha256("plain-transitive-bytes"))
                .replace("LINUX_SHA", sha256("linux-transitive-bytes")));

        ClasspathSet classpaths =
                service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/api");

        assertTrue(classpaths.compile().entries().contains(apiJar));
        assertTrue(classpaths.compile().entries().contains(linuxJar));
        assertFalse(classpaths.compile().entries().contains(plainJar));
    }

    private Workspace workspace(
            List<String> members,
            List<WorkspaceProjectEdge> edges) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), "");
        for (String member : members) {
            Files.createDirectories(tempDir.resolve(member));
        }
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig("acme-platform", members, List.of(), Map.of(), Map.of()),
                members.stream()
                        .map(member -> new WorkspaceMember(member, tempDir.resolve(member), null))
                        .toList(),
                edges,
                members);
    }

    private static void createEmptyFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
