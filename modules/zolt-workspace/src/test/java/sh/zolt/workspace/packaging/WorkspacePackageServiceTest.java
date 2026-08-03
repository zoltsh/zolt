package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspacePackageServiceTest {
    private final WorkspacePackageService service = new WorkspacePackageService();

    @TempDir
    private Path tempDir;

    @Test
    void packagesSelectedWorkspaceMembersAfterBuildingDependencies() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core", "apps/worker"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """
                main = "com.acme.api.Api"

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static void main(String[] args) {
                        System.out.println(Core.message());
                    }
                }
                """);
        member("apps/worker", "worker", "");
        source("apps/worker/src/main/java/com/acme/worker/Worker.java", """
                package com.acme.worker;

                public final class Worker {
                }
                """);

        WorkspacePackageResult result = service.packageJars(
                tempDir.resolve("apps/api"),
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertTrue(result.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspacePackageResult.MemberPackageResult::member)
                .toList());
        assertEquals(1, result.entryCount());

        Path apiJar = tempDir.resolve("apps/api/target/api-0.1.0.jar");
        assertTrue(Files.exists(apiJar));
        assertFalse(Files.exists(tempDir.resolve("modules/core/target/core-0.1.0.jar")));
        assertFalse(Files.exists(tempDir.resolve("apps/worker/target/worker-0.1.0.jar")));
        assertFalse(Files.exists(tempDir.resolve("apps/worker/target/classes/com/acme/worker/Worker.class")));

        try (JarFile jar = new JarFile(apiJar.toFile())) {
            assertNotNull(jar.getEntry("com/acme/api/Api.class"));
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("com.acme.api.Api", attributes.getValue(Attributes.Name.MAIN_CLASS));
        }
    }

    @Test
    void packagesUberWorkspaceMemberWithWorkspaceDependencyClasses() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """
                main = "com.acme.api.Api"

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }

                [package]
                mode = "uber"
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static void main(String[] args) {
                        System.out.println(Core.message());
                    }
                }
                """);

        WorkspacePackageResult result = service.packageJars(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(1, result.members().size());
        Path apiJar = tempDir.resolve("apps/api/target/api-0.1.0.jar");
        assertTrue(Files.exists(apiJar));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/api-0.1.0.runtime-classpath")));
        try (JarFile jar = new JarFile(apiJar.toFile())) {
            assertNotNull(jar.getEntry("com/acme/api/Api.class"));
            assertNotNull(jar.getEntry("com/acme/core/Core.class"));
        }
    }

    @Test
    void packagesWorkspaceMemberJavadocsWithWorkspaceCompileClasspath() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/api", "modules/binding"]
                """);
        member("modules/api", "api", "");
        source("modules/api/src/main/java/com/acme/api/Logger.java", """
                package com.acme.api;

                /** Public logging API. */
                public interface Logger {
                    /** Logs a message. */
                    void info(String message);
                }
                """);
        member("modules/binding", "binding", """

                [api.dependencies]
                "com.acme:api" = { workspace = "modules/api" }

                [package]
                sources = true
                javadoc = true
                """);
        source("modules/binding/src/main/java/com/acme/binding/SimpleLogger.java", """
                package com.acme.binding;

                import com.acme.api.Logger;

                /** Simple logger implementation. */
                public final class SimpleLogger implements Logger {
                    /** Logs a message. */
                    public void info(String message) {
                    }
                }
                """);

        WorkspacePackageResult result = service.packageJars(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("modules/binding")));

        assertEquals(List.of("sources", "javadoc"), result.members().getFirst().result().artifacts().stream()
                .map(sh.zolt.build.packaging.PackageArtifact::classifier)
                .toList());
        Path javadocJar = tempDir.resolve("modules/binding/target/binding-0.1.0-javadoc.jar");
        assertTrue(Files.exists(javadocJar));
        try (JarFile jar = new JarFile(javadocJar.toFile())) {
            assertNotNull(jar.getEntry("com/acme/binding/SimpleLogger.html"));
        }
    }

    @Test
    void reusesCurrentWorkspacePackagesAndReportsBoundedWorkers() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/alpha", "modules/beta"]
                """);
        member("modules/alpha", "alpha", "");
        member("modules/beta", "beta", "");
        source("modules/alpha/src/main/java/com/acme/alpha/Alpha.java", """
                package com.acme.alpha;
                public final class Alpha {
                }
                """);
        source("modules/beta/src/main/java/com/acme/beta/Beta.java", """
                package com.acme.beta;
                public final class Beta {
                }
                """);
        WorkspaceSelectionRequest all = new WorkspaceSelectionRequest(true, List.of());

        WorkspacePackageResult first = service.packageJars(
                tempDir,
                tempDir.resolve("cache"),
                all);
        WorkspacePackageResult second = service.packageJars(
                tempDir,
                tempDir.resolve("cache"),
                all);

        assertEquals(2, first.packagedCount());
        assertEquals(0, first.reusedCount());
        assertEquals(Math.min(2, Runtime.getRuntime().availableProcessors()), first.maxWorkers());
        assertEquals(0, second.packagedCount());
        assertEquals(2, second.reusedCount());
        assertEquals(List.of("modules/alpha", "modules/beta"), second.members().stream()
                .map(WorkspacePackageResult.MemberPackageResult::member)
                .toList());
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                %s""".formatted(name, currentJavaMajorVersion(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
