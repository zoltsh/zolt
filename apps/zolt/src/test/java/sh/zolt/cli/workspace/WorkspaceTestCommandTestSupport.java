package sh.zolt.cli.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspaceTestCommandTestSupport {
    private WorkspaceTestCommandTestSupport() {}

    static void writeWorkspaceTestLockfile(Path workspaceDir) throws IOException {
        Files.writeString(workspaceDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "com.example:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                dependencies = []
                """);
    }
}
