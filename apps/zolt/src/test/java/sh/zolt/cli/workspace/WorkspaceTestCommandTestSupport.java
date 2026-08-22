package sh.zolt.cli.workspace;

import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspaceTestCommandTestSupport {
    private WorkspaceTestCommandTestSupport() {}

    static void writeWorkspaceTestLockfile(Path workspaceDir, Path cacheRoot, String... members) throws IOException {
        String memberList = String.join("\", \"", members);
        String dependencyRoots = java.util.Arrays.stream(members)
                .sorted()
                .map(member -> """
                [[dependencyRoot]]
                member = "%s"
                id = "com.example:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                """.formatted(member))
                .collect(java.util.stream.Collectors.joining());
        write(workspaceDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                %s

                [[package]]
                id = "com.example:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["%s"]
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                dependencies = []
                members = ["%s"]
                """.formatted(dependencyRoots, memberList, memberList));
    }
}
