package sh.zolt.workspace.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

abstract class WorkspaceBuildServiceTestSupport {
    @TempDir
    protected Path tempDir;

    protected void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
    }

    protected void member(String path, String name, String extraToml) throws IOException {
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

    protected void source(String path, String content) throws IOException {
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
