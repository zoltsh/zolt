package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.publish.PublishPomGenerator;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.publish.WorkspaceMemberPomLockProjection;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkspaceOptionalDependencyParityTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void optionalDependenciesStayLocalButRemainPublishedAsOptional() throws IOException {
        addArtifact("com.example", "optional-api", "1.0.0", pom("com.example", "optional-api", "1.0.0"));
        addArtifact("com.example", "optional-impl", "1.0.0", pom("com.example", "optional-impl", "1.0.0"));
        addArtifact("com.example", "optional-runtime", "1.0.0", pom("com.example", "optional-runtime", "1.0.0"));
        workspace("""
                [workspace]
                name = "optional-parity"
                members = ["modules/feature-api", "modules/feature-impl", "modules/core", "apps/app"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("modules/feature-api", "feature-api", "");
        member("modules/feature-impl", "feature-impl", "");
        member("modules/core", "core", """

                [api.dependencies]
                "com.example:optional-api" = { version = "1.0.0", optional = true }
                "com.acme:feature-api" = { workspace = "modules/feature-api", optional = true }

                [dependencies]
                "com.example:optional-impl" = { version = "1.0.0", optional = true }
                "com.acme:feature-impl" = { workspace = "modules/feature-impl", optional = true }

                [runtime.dependencies]
                "com.example:optional-runtime" = { version = "1.0.0", optional = true }
                """);
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);

        Path cache = tempDir.resolve("cache");
        service.resolve(tempDir, cache, false, false);
        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        Workspace workspace = new WorkspaceDiscoveryService()
                .discover(tempDir)
                .orElseThrow();
        WorkspaceClasspathService classpaths = new WorkspaceClasspathService();

        var core = classpaths.classpathsFor(
                workspace, lockfile, cache, "modules/core");
        assertContains(core.compile(), "optional-api-1.0.0.jar");
        assertContains(core.compile(), "optional-impl-1.0.0.jar");
        assertContains(core.compile(), "modules/feature-api/target/classes");
        assertContains(core.compile(), "modules/feature-impl/target/classes");
        assertContains(core.runtime(), "optional-runtime-1.0.0.jar");

        var app = classpaths.classpathsFor(
                workspace, lockfile, cache, "apps/app");
        assertAbsent(app.compile(), "optional-api");
        assertAbsent(app.compile(), "optional-impl");
        assertAbsent(app.compile(), "feature-api");
        assertAbsent(app.compile(), "feature-impl");
        assertAbsent(app.runtime(), "optional-api");
        assertAbsent(app.runtime(), "optional-impl");
        assertAbsent(app.runtime(), "optional-runtime");
        assertAbsent(app.runtime(), "feature-api");
        assertAbsent(app.runtime(), "feature-impl");

        Map<String, List<ResolvedClasspathPackage>> packageInputs =
                classpaths.classpathPackagesForMembers(
                        workspace, lockfile, cache, List.of("apps/app"));
        assertFalse(packageInputs.get("apps/app").stream()
                .anyMatch(candidate -> candidate.resolvedPackage()
                        .packageId()
                        .artifactId()
                        .startsWith("optional-")));

        WorkspaceMember coreMember = workspace.members().stream()
                .filter(member -> member.path().equals("modules/core"))
                .findFirst()
                .orElseThrow();
        ZoltLockfile projected = new WorkspaceMemberPomLockProjection()
                .project(coreMember.path(), coreMember.config(), lockfile);
        String pomXml = new PublishPomGenerator()
                .generate(coreMember.config(), projected);
        assertTrue(pomXml.contains("<artifactId>optional-api</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>optional-impl</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>optional-runtime</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>feature-api</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>feature-impl</artifactId>"));
        assertTrue(
                count(pomXml, "<optional>true</optional>") >= 5,
                pomXml);
    }

    private static void assertContains(
            Classpath classpath,
            String fragment) {
        assertTrue(classpath.entries().stream()
                .map(Path::toString)
                .anyMatch(value -> value.contains(fragment)));
    }

    private static void assertAbsent(
            Classpath classpath,
            String fragment) {
        assertFalse(classpath.entries().stream()
                .map(Path::toString)
                .anyMatch(value -> value.contains(fragment)));
    }

    private static int count(
            String value,
            String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
