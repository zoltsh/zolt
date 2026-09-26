package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceBuildServiceIncrementalUpstreamTest {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @TempDir
    private Path tempDir;

    @Test
    void upstreamImplementationOnlyChangeDoesNotInvalidateDependentWorkspaceCompilation() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core", "modules/util", "apps/api"]
                """);
        member("modules/core", "core", "");
        Path coreSource = tempDir.resolve("modules/core/src/main/java/com/acme/core/Core.java");
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
        member("modules/util", "util", "");
        source("modules/util/src/main/java/com/acme/util/Util.java", """
                package com.acme.util;

                public final class Util {
                    private Util() {
                    }

                    public static String message() {
                        return "util";
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                "com.acme:util" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;
                import com.acme.util.Util;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message() + Util.message();
                    }
                }
                """);
        service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        Files.writeString(coreSource, """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core changed";
                    }
                }
                """);

        WorkspaceBuildResult result = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        Map<String, Boolean> skippedByMember = new HashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            skippedByMember.put(member.member(), member.result().mainCompilationSkipped());
        }

        assertFalse(skippedByMember.get("modules/core"));
        assertTrue(skippedByMember.get("modules/util"));
        assertTrue(skippedByMember.get("apps/api"));
    }

    @Test
    void upstreamAbiChangeInvalidatesDependentWorkspaceCompilation() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core", "modules/util", "apps/api"]
                """);
        member("modules/core", "core", "");
        Path coreSource = tempDir.resolve("modules/core/src/main/java/com/acme/core/Core.java");
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
        member("modules/util", "util", "");
        source("modules/util/src/main/java/com/acme/util/Util.java", """
                package com.acme.util;

                public final class Util {
                    private Util() {
                    }

                    public static String message() {
                        return "util";
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                "com.acme:util" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;
                import com.acme.util.Util;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message() + Util.message();
                    }
                }
                """);
        service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        Files.writeString(coreSource, """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core changed";
                    }

                    public static String extra() {
                        return "extra";
                    }
                }
                """);

        WorkspaceBuildResult result = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        Map<String, Boolean> skippedByMember = new HashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            skippedByMember.put(member.member(), member.result().mainCompilationSkipped());
        }

        assertFalse(skippedByMember.get("modules/core"));
        assertTrue(skippedByMember.get("modules/util"));
        assertFalse(skippedByMember.get("apps/api"));
    }

    @Test
    void genericAbiChangeFailsIncrementalAndCleanWorkspaceBuildsEqually() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core", "apps/api"]
                """);
        member("modules/core", "core", "");
        Path coreSource = tempDir.resolve("modules/core/src/main/java/com/acme/core/Core.java");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                import java.util.List;

                public final class Core {
                    public List<String> values() {
                        return List.of("value");
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    public String call(Core core) {
                        return core.values().get(0);
                    }
                }
                """);
        service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        Files.writeString(coreSource, """
                package com.acme.core;

                import java.util.List;

                public final class Core {
                    public List<Integer> values() {
                        return List.of(1);
                    }
                }
                """);

        JavacException incremental = assertThrows(
                JavacException.class,
                () -> service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false));

        deleteTree(tempDir.resolve("modules/core/target"));
        deleteTree(tempDir.resolve("apps/api/target"));
        Files.deleteIfExists(tempDir.resolve(".zolt/workspace-state-v1"));
        JavacException clean = assertThrows(
                JavacException.class,
                () -> service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false));

        assertTrue(incremental.getMessage().contains("Integer cannot be converted to String"));
        assertTrue(clean.getMessage().contains("Integer cannot be converted to String"));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = %s
                %s""".formatted(name, currentJavaMajorVersion(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
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

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
