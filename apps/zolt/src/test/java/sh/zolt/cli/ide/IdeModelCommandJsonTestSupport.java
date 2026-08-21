package sh.zolt.cli.ide;

import static sh.zolt.cli.ContentAddressedLockTestSupport.migrate;

import static sh.zolt.cli.ide.IdeModelCommandTestSupport.currentJavaMajorVersion;
import static sh.zolt.cli.ide.IdeModelCommandTestSupport.jsonPath;
import static sh.zolt.cli.ide.IdeModelCommandTestSupport.writeProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;

abstract class IdeModelCommandJsonTestSupport {
    protected static final String APP_JAR_PATH =
            "com/example/app/1.0.0/app-1.0.0.jar";
    protected static final String TEST_JAR_PATH =
            "com/example/test-lib/1.0.0/test-lib-1.0.0.jar";

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

    protected static void writeLockfile(Path projectDir, Path cacheRoot) throws IOException {
        new ZoltLockfileWriter().write(projectDir.resolve("zolt.lock"), migrate(cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:app"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "."
                id = "com.example:test-lib"
                version = "1.0.0"
                lane = "test"
                resolvedScope = "test"

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
                """.formatted(APP_JAR_PATH, TEST_JAR_PATH)));
    }
}
