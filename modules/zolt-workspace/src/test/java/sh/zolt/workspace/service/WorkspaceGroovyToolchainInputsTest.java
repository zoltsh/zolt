package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.lang.GroovyObject;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;

final class WorkspaceGroovyToolchainInputsTest
        extends WorkspaceBuildServiceDependencyVisibilityTestSupport {
    private static final String GROOVY_VERSION = "4.0.22";

    @Test
    void configuredGroovyToolchainCompilesMainSourcesFromVerifiedWorkspacePackages()
            throws Exception {
        addPrebuiltJarArtifact("org.apache.groovy", "groovy", GROOVY_VERSION, groovyJar());
        writeWorkspace("groovy-main-toolchain");
        member("apps/app", "app", groovyConfig("dependencies"));
        source("apps/app/src/main/java/com/acme/App.groovy", """
                package com.acme

                final class App {
                    static String message() {
                        "workspace-groovy"
                    }
                }
                """);

        WorkspaceBuildResult result = new WorkspaceBuildService()
                .build(tempDir.resolve("apps/app"), tempDir.resolve("cache"), false);

        assertEquals(1, result.mainCompilationExecutedCount());
        assertToolProjection(result.members().getFirst());
        assertTrue(result.members().getFirst().classpathPackages().isEmpty());
        assertTrue(Files.isRegularFile(
                tempDir.resolve("apps/app/target/classes/com/acme/App.class")));
    }

    @Test
    void configuredGroovyToolchainCompilesTestOnlySourcesFromVerifiedWorkspacePackages()
            throws Exception {
        addPrebuiltJarArtifact("org.apache.groovy", "groovy", GROOVY_VERSION, groovyJar());
        Path consoleJar = tempDir.resolve("fixture-jars/junit-platform-console-1.11.4.jar");
        WorkspaceTestServiceTestSupport.createFakeConsoleJar(tempDir, consoleJar);
        addPrebuiltJarArtifact(
                "org.junit.platform",
                "junit-platform-console",
                "1.11.4",
                consoleJar);
        writeWorkspace("groovy-test-toolchain");
        member("apps/app", "app", groovyConfig("dependencies.test") + """

                [test.sources]
                groovy = ["src/test/groovy"]
                """);
        source("apps/app/src/main/java/com/acme/App.java", """
                package com.acme;

                public final class App {
                }
                """);
        source("apps/app/src/test/groovy/com/acme/AppSpec.groovy", """
                package com.acme

                final class AppSpec {
                    static String message() {
                        "workspace-test-groovy"
                    }
                }
                """);
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceTestService service = new WorkspaceTestService();
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir.resolve("apps/app")),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());

        WorkspaceBuildResult build = service.buildTestCompileInputs(plan, cacheRoot);
        WorkspaceTestCompileResult compiled = service.compileTests(plan, build);

        assertToolProjection(build.members().getFirst());
        assertTrue(build.members().getFirst().classpathPackages().isEmpty());
        assertEquals(1, compiled.members().getFirst().result().sourceCount());
        assertTrue(Files.isRegularFile(
                tempDir.resolve("apps/app/target/test-classes/com/acme/AppSpec.class")));
    }

    private void writeWorkspace(String name) throws Exception {
        workspace("""
                [workspace]
                name = "%s"

                [workspace.members]
                include = ["apps/app"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(name, baseUri));
    }

    private static String groovyConfig(String dependencyTable) {
        return """

                [toolchain.groovy]
                version = "%s"

                [%s]
                "org.apache.groovy:groovy" = "%s"
                """.formatted(GROOVY_VERSION, dependencyTable, GROOVY_VERSION);
    }

    private static void assertToolProjection(
            WorkspaceBuildResult.MemberBuildResult member) {
        List<DependencyScope> scopes = member.verifiedCompilerPackages().stream()
                .filter(dependency -> "org.apache.groovy:groovy"
                        .equals(dependency.resolvedPackage().packageId().toString()))
                .map(dependency -> dependency.scope())
                .toList();
        assertTrue(scopes.contains(DependencyScope.TOOL_GROOVY), scopes.toString());
    }

    private static Path groovyJar() throws URISyntaxException {
        return Path.of(GroovyObject.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }
}
