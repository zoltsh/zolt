package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceClassifiedShadowingTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void exactWorkspaceScopeDoesNotShadowClassifiedAttachmentAtAnotherVersion() throws IOException {
        addArtifact("com.acme", "core", "2.8.7", pom("com.acme", "core", "2.8.7"));
        addClassifierJar("com.acme", "core", "2.8.7", "tests");
        addArtifact(
                "org.junit.platform",
                "junit-platform-console",
                "1.11.4",
                pom("org.junit.platform", "junit-platform-console", "1.11.4"));
        workspace("""
                [workspace]
                name = "classified-shadow"
                members = ["modules/core", "apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", "");
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }

                [test.dependencies]
                "com.acme:core" = { version = "2.8.7", classifier = "tests" }
                """);

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        List<LockPackage> coreEntries = lockfile.packages().stream()
                .filter(lockPackage ->
                        lockPackage.packageId().equals(new PackageId("com.acme", "core")))
                .toList();
        assertEquals(2, coreEntries.size());
        assertTrue(coreEntries.stream().anyMatch(lockPackage ->
                lockPackage.workspace().equals(Optional.of("modules/core"))
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.version().equals("0.1.0")));
        LockPackage attachment = coreEntries.stream()
                .filter(lockPackage ->
                        LockArtifactVariant.of(lockPackage).classifier().equals(Optional.of("tests")))
                .findFirst()
                .orElseThrow();
        assertEquals("2.8.7", attachment.version());
        assertEquals(DependencyScope.TEST, attachment.scope());
        assertTrue(lockfile.conflicts().isEmpty());
    }

    private void addClassifierJar(
            String groupId,
            String artifactId,
            String version,
            String classifier) {
        String base = "/maven2/"
                + groupId.replace('.', '/')
                + "/"
                + artifactId
                + "/"
                + version
                + "/"
                + artifactId
                + "-"
                + version;
        responses.put(
                base + "-" + classifier + ".jar",
                "classified".getBytes(StandardCharsets.UTF_8));
    }
}
