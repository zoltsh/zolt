package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof of the lane-closure invariant: a lock package attributed to a <em>dependency</em>
 * member sits on the dependent's test lane, so bumping it has to reach the dependent.
 *
 * <p>Before the lane closure, the dependent's per-member digest folded only its own attributed
 * bucket. The bump moved apps/api's test classpath and left its key alone, and stage 0 declared the
 * member current — the test run then executed against classes compiled for the previous version.
 */
final class WorkspaceLockLaneAdmissionTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException {
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"
                members = ["modules/core", "apps/api"]
                """);
        member(tempDir, "modules/core", "core", "");
        source(tempDir, "modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                }
                """);
        source(tempDir, "modules/core/src/test/java/com/acme/core/CoreTest.java", """
                package com.acme.core;

                public final class CoreTest {
                }
                """);
        member(tempDir, "apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source(tempDir, "apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                }
                """);
        source(tempDir, "apps/api/src/test/java/com/acme/api/ApiTest.java", """
                package com.acme.api;

                public final class ApiTest {
                }
                """);
    }

    @Test
    void aDependencyAttributedPackageBumpRecompilesTheDependentsTests() throws IOException {
        compile();
        attributeRuntimePackageToCore("1.0.0");
        compile();

        attributeRuntimePackageToCore("2.0.0");
        Compilation result = compile();

        assertFalse(
                skipped(result).get("apps/api"),
                "the dependent's test classpath moved, so its test classes are stale");
        assertTrue(
                result.metrics().testClasspathCalculations() > 0,
                "the dependent had to project the moved test lane");
    }

    /** The same bump must not disturb a member the package cannot reach. */
    @Test
    void aBumpLeavesMembersOutsideTheAttributedClosureAlone() throws IOException {
        compile();
        attributeRuntimePackageToApiOnly("1.0.0");
        compile();

        attributeRuntimePackageToApiOnly("2.0.0");
        Compilation result = compile();

        assertTrue(
                skipped(result).get("modules/core"),
                "a package attributed to apps/api alone cannot move the leaf member's lanes");
        assertEquals(
                1,
                result.metrics().memberPipelineInvocations(),
                "only the member the package names rebuilds");
    }

    private void attributeRuntimePackageToCore(String version) throws IOException {
        writeAttributedPackage(version, "modules/core");
    }

    private void attributeRuntimePackageToApiOnly(String version) throws IOException {
        writeAttributedPackage(version, "apps/api");
    }

    /**
     * Rewrites the lock so one external package at {@code version} is attributed to {@code member}
     * alone, with its cached jar present and matching, exactly as a resolve would leave it.
     */
    private void writeAttributedPackage(String version, String member) throws IOException {
        String jarPath = "org/example/attributed/" + version + "/attributed-" + version + ".jar";
        Path jar = tempDir.resolve("cache").resolve(jarPath);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("org/example/attributed/marker.txt"));
            output.write(("attributed " + version).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        String jarSha256 = sha256(Files.readAllBytes(jar));
        Path lock = tempDir.resolve("zolt.lock");
        String content = Files.readString(lock);
        int marker = content.indexOf("\n# attributed-package\n");
        if (marker >= 0) {
            content = content.substring(0, marker);
        }
        Files.writeString(lock, content + """

                # attributed-package
                [[package]]
                id = "org.example:attributed"
                version = "%s"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "%s"
                jarSha256 = "%s"
                members = ["%s"]
                dependencies = []
                """.formatted(version, jarPath, jarSha256, member));
    }

    private static Map<String, Boolean> skipped(Compilation compilation) {
        return compilation.compiled().members().stream()
                .collect(Collectors.toMap(
                        WorkspaceTestCompileResult.MemberTestCompileResult::member,
                        entry -> entry.result().testCompilationSkipped()));
    }

    private Compilation compile() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult build = service.buildTestCompileInputs(plan, cacheRoot);
        WorkspaceTestCompileResult compiled = service.compileTests(plan, build);
        return new Compilation(plan.executionContext().metrics(), compiled);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Compilation(
            WorkspaceExecutionContext.Metrics metrics,
            WorkspaceTestCompileResult compiled) {
    }
}
