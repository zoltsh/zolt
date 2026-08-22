package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace command projects its lockfile once per member and lane. These tests pin the cost of
 * that fan-out: the artifacts behind it are read once per command, not once per projection.
 */
final class WorkspaceClasspathArtifactIndexTest {
    private static final List<String> MEMBERS = List.of("apps/api", "modules/core");
    private static final String SHARED_JAR_BYTES = "shared jar bytes";
    private static final String SHARED_POM_BYTES = "shared pom bytes";

    private final WorkspaceClasspathService service = new WorkspaceClasspathService();

    @TempDir
    private Path tempDir;

    @Test
    void readsEachCachedArtifactOnceForTheWholeTestRun() throws IOException {
        WorkspaceExecutionContext context = testRunContext();

        service.classpathsForMembers(context, MEMBERS, testRunRequirements());

        VerifiedArtifactIndex.Metrics metrics = context.metrics().artifactIntegrity();
        // Two files back the shared dependency; every member and lane wants both of them.
        assertEquals(2, metrics.hashes(), "each cached artifact must be read exactly once");
        assertEquals(2, metrics.paths());
        assertEquals(metrics.paths(), metrics.hashes());
        assertTrue(
                metrics.cacheHits() > 0,
                "later lock projections must reuse the digests instead of re-reading");
        assertTrue(metrics.bytes() > 0L);
        assertTrue(metrics.nanos() > 0L);
    }

    @Test
    void addsNoFurtherReadsWhenTheSameCommandAsksAgain() throws IOException {
        WorkspaceExecutionContext context = testRunContext();

        service.classpathsForMembers(context, MEMBERS, testRunRequirements());
        int hashesAfterFirstPass = context.metrics().artifactIntegrity().hashes();
        service.classpathPackagesForMembers(context, MEMBERS);
        service.packageInputsFor(context, "apps/api", true);

        assertEquals(2, hashesAfterFirstPass);
        assertEquals(hashesAfterFirstPass, context.metrics().artifactIntegrity().hashes());
    }

    @Test
    void reusesTheIndexPopulatedByWorkspaceFreshnessAcrossEveryMember() throws IOException {
        Path cacheRoot = tempDir.resolve("freshness-cache");
        writeFile(cacheRoot.resolve("org/example/shared/1.0.0/shared-1.0.0.jar"), SHARED_JAR_BYTES);
        writeFile(cacheRoot.resolve("org/example/shared/1.0.0/shared-1.0.0.pom"), SHARED_POM_BYTES);
        ZoltLockfile lockfile = lockfile(cacheRoot);
        VerifiedArtifactIndex index = new VerifiedArtifactIndex();
        new ArtifactIntegrityVerifier(index).verify(lockfile, cacheRoot);
        WorkspaceExecutionContext context = new WorkspaceExecutionContext(
                workspace(), lockfile, cacheRoot, index);

        service.classpathsForMembers(context, MEMBERS, testRunRequirements());

        assertEquals(2, context.metrics().artifactIntegrity().hashes());
        assertTrue(context.metrics().artifactIntegrity().cacheHits() > 0);
    }

    @Test
    void startsEmptyForEveryCommandSoModifiedArtifactsAreReadAgain() throws IOException {
        service.classpathsForMembers(testRunContext(), MEMBERS, testRunRequirements());

        // A second command gets a second context, and therefore a second index: nothing about the
        // first command's reads may be carried over, or a cache edited in between would go unseen.
        WorkspaceExecutionContext nextCommand = testRunContext();
        assertEquals(0, nextCommand.metrics().artifactIntegrity().hashes());

        service.classpathsForMembers(nextCommand, MEMBERS, testRunRequirements());

        assertEquals(2, nextCommand.metrics().artifactIntegrity().hashes());
    }

    private static Map<String, WorkspaceBuildRequirements> testRunRequirements() {
        Map<String, WorkspaceBuildRequirements> requirements = new LinkedHashMap<>();
        MEMBERS.forEach(member -> requirements.put(member, WorkspaceBuildRequirements.testRun()));
        return requirements;
    }

    private WorkspaceExecutionContext testRunContext() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        writeFile(cacheRoot.resolve("org/example/shared/1.0.0/shared-1.0.0.jar"), SHARED_JAR_BYTES);
        writeFile(cacheRoot.resolve("org/example/shared/1.0.0/shared-1.0.0.pom"), SHARED_POM_BYTES);
        return new WorkspaceExecutionContext(workspace(), lockfile(cacheRoot), cacheRoot);
    }

    private ZoltLockfile lockfile(Path cacheRoot) throws IOException {
        return WorkspaceContentAddressedLockTestSupport.migrate(cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.acme:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.example:shared"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "modules/core"
                id = "org.example:shared"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

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
                id = "org.example:shared"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/shared/1.0.0/shared-1.0.0.jar"
                pom = "org/example/shared/1.0.0/shared-1.0.0.pom"
                jarSha256 = "%s"
                pomSha256 = "%s"
                members = ["apps/api", "modules/core"]
                dependencies = []
                """.formatted(sha256(SHARED_JAR_BYTES), sha256(SHARED_POM_BYTES)));
    }

    private Workspace workspace() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), "");
        for (String member : MEMBERS) {
            Files.createDirectories(tempDir.resolve(member));
        }
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt.toml"),
                new WorkspaceConfig("acme-platform", MEMBERS, List.of(), Map.of(), Map.of()),
                MEMBERS.stream()
                        .map(member -> new WorkspaceMember(member, tempDir.resolve(member), null))
                        .toList(),
                List.of(new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")),
                MEMBERS);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
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
