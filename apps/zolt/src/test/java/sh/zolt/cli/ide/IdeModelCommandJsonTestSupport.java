package sh.zolt.cli.ide;

import static sh.zolt.cli.ide.IdeModelCommandTestSupport.currentJavaMajorVersion;
import static sh.zolt.cli.ide.IdeModelCommandTestSupport.jsonPath;
import static sh.zolt.cli.ide.IdeModelCommandTestSupport.writeProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

abstract class IdeModelCommandJsonTestSupport {
    protected static final String APP_JAR_PATH =
            "blobs/v2/sha256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/app-1.0.0.jar";
    protected static final String TEST_JAR_PATH =
            "blobs/v2/sha256/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/test-lib-1.0.0.jar";

    protected static Path writeProject(Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        return projectDir;
    }

    protected static Path cacheRoot(Path tempDir) {
        return tempDir.resolve("cache");
    }

    protected static Path root(Path projectDir) {
        return projectDir.toAbsolutePath().normalize();
    }

    protected static String currentJavaMajorVersionValue() {
        return currentJavaMajorVersion();
    }

    protected static String jsonPathValue(Path path) {
        return jsonPath(path);
    }

    protected static void writeLockfile(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:app"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "%s"
                dependencies = []

                [[package]]
                id = "com.example:test-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "%s"
                dependencies = []
                """.formatted(APP_JAR_PATH, TEST_JAR_PATH));
    }
}
