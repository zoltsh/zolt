package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.NativeImageException;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceNativeBuildServiceTest {
    private final WorkspaceNativeBuildService service = new WorkspaceNativeBuildService();

    @TempDir
    private Path tempDir;

    @Test
    void preservesConfiguredUberJarForWorkspaceNativeBuild() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                defaultMembers = ["apps/api"]
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
        Path cacheRoot = tempDir.resolve("cache");
        WorkspacePackageResult packaged = new WorkspacePackageService().packageJars(
                tempDir,
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        assertTrue(packaged.resolvedLockfile());
        Path jar = tempDir.resolve("apps/api/target/api-0.1.0.jar");
        byte[] packagedBytes = Files.readAllBytes(jar);
        Path nativeImage = fakeNativeImage(tempDir.resolve("native-image"));

        WorkspaceNativeBuildResult result = service.buildNative(
                tempDir,
                cacheRoot,
                WorkspaceSelectionRequest.defaults(),
                (workspace, member, config) -> nativeImage,
                () -> {
                });

        assertFalse(result.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceNativeBuildResult.MemberNativeBuildResult::member)
                .toList());
        assertEquals(PackageMode.UBER, result.members().getFirst().result().packageResult().mode());
        Path nativeInputJar = tempDir.resolve("apps/api/target/native/input/api-0.1.0.jar");
        assertEquals(
                nativeInputJar,
                result.members().getFirst().result().packageResult().jarPath());
        assertArrayEquals(packagedBytes, Files.readAllBytes(jar));
        try (JarFile archive = new JarFile(nativeInputJar.toFile())) {
            assertNotNull(archive.getEntry("com/acme/api/Api.class"));
            assertNotNull(archive.getEntry("com/acme/core/Core.class"));
        }
        Path binary = tempDir.resolve("apps/api/target/native/api");
        assertTrue(Files.exists(binary));
        assertFalse(Files.exists(tempDir.resolve("modules/core/target/native/core")));
        String log = Files.readString(tempDir.resolve("apps/api/target/native/native-image.log"));
        assertTrue(log.contains("executable=" + nativeImage));
        assertTrue(log.contains("classpath=" + nativeInputJar));
        assertFalse(log.contains(tempDir.resolve("modules/core/target/classes").toString()));
    }

    @Test
    void selectedLibraryMemberWithoutMainClassProducesWorkspaceDiagnostic() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                }
                """);

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        tempDir,
                        tempDir.resolve("cache"),
                        new WorkspaceSelectionRequest(false, List.of("modules/core")),
                        fakeNativeImage(tempDir.resolve("native-image"))));

        assertEquals(
                "Workspace member `modules/core` has no main class configured. Add [project].main to its zolt.toml or choose an application member.",
                exception.getMessage());
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

    private static Path fakeNativeImage(Path binary) throws IOException {
        Files.writeString(binary, """
                #!/usr/bin/env bash
                set -euo pipefail

                classpath=""
                output=""
                while [[ "$#" -gt 0 ]]; do
                  case "$1" in
                    -cp)
                      shift
                      classpath="$1"
                      ;;
                    -o)
                      shift
                      output="$1"
                      ;;
                  esac
                  shift || true
                done

                mkdir -p "$(dirname "$output")"
                printf 'native\\n' > "$output"
                chmod +x "$output"
                printf 'executable=%s\\n' "$0"
                printf 'classpath=%s\\n' "$classpath"
                printf 'output=%s\\n' "$output"
                """);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
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
