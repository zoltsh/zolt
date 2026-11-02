package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;

final class WorkspaceTimestampPreservingEditTest extends WorkspaceBuildServiceTestSupport {
    @Test
    void unexpectedClassInWarmOutputIsRemovedBeforeReuse() throws Exception {
        workspace("""
                [workspace]
                name = "unexpected-output"
                [workspace.members]
                include = ["app"]
                """);
        member("app", "app", "");
        source("app/src/main/java/com/acme/App.java", "package com.acme; public class App {}");
        var service = new WorkspaceBuildService();
        service.build(tempDir, tempDir.resolve("cache"), false);
        var extra = tempDir.resolve("app/target/classes/com/acme/Stale.class");
        Files.copy(tempDir.resolve("app/target/classes/com/acme/App.class"), extra);

        WorkspaceBuildResult result = service.build(tempDir, tempDir.resolve("cache"), false);

        assertEquals(1, result.mainCompilationExecutedCount());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(extra));
    }

    @Test
    void sameSizeSourceEditWithRestoredTimestampRecompilesAndRunsCurrentCode() throws Exception {
        workspace("""
                [workspace]
                name = "preserved-timestamps"
                [workspace.members]
                include = ["app"]
                """);
        member("app", "app", "");
        String original = "package com.acme; public class App { public static String value() { return \"first\"; } }";
        source("app/src/main/java/com/acme/App.java", original);
        var source = tempDir.resolve("app/src/main/java/com/acme/App.java");
        Files.setLastModifiedTime(source, FileTime.fromMillis(1_600_000_000_000L));
        FileTime modified = Files.getLastModifiedTime(source);
        var service = new WorkspaceBuildService();
        service.build(tempDir, tempDir.resolve("cache"), false);
        Files.writeString(source, original.replace("first", "other"));
        Files.setLastModifiedTime(source, modified);

        WorkspaceBuildResult result = service.build(tempDir, tempDir.resolve("cache"), false);

        assertEquals(1, result.mainCompilationExecutedCount());
        try (var loader = new URLClassLoader(new java.net.URL[] {
                tempDir.resolve("app/target/classes").toUri().toURL()}, null)) {
            assertEquals("other", loader.loadClass("com.acme.App").getMethod("value").invoke(null));
        }
        assertEquals(0, service.build(tempDir, tempDir.resolve("cache"), false)
                .executionMetrics().memberPipelineInvocations());
    }
}
