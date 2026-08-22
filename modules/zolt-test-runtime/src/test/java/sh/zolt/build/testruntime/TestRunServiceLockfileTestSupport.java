package sh.zolt.build.testruntime;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.build.TestContentAddressedLockSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TestRunServiceLockfileTestSupport {
    private TestRunServiceLockfileTestSupport() {
    }

    static void writeConsoleLockfile(Path projectDir) throws IOException {
        write(projectDir, """
                version = 7

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                dependencies = []
                """);
    }

    static void writeConsoleAndJbossLogManagerLockfile(Path projectDir) throws IOException {
        write(projectDir, """
                version = 7

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.jboss.logmanager:jboss-logmanager"
                version = "3.1.2.Final"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/jboss/logmanager/jboss-logmanager/3.1.2.Final/jboss-logmanager-3.1.2.Final.jar"
                dependencies = []
                """);
    }

    static void writeNonStandaloneConsoleLockfile(Path projectDir) throws IOException {
        write(projectDir, """
                version = 7

                [[package]]
                id = "org.apiguardian:apiguardian-api"
                version = "1.1.2"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar"
                dependencies = []

                [[package]]
                id = "org.junit.jupiter:junit-jupiter-engine"
                version = "5.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-commons"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-commons/1.11.4/junit-platform-commons-1.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-console"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-console/1.11.4/junit-platform-console-1.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-engine"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-engine/1.11.4/junit-platform-engine-1.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-launcher"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-reporting"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-reporting/1.11.4/junit-platform-reporting-1.11.4.jar"
                dependencies = []

                [[package]]
                id = "org.opentest4j:opentest4j"
                version = "1.3.0"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"
                dependencies = []
                """);
    }

    static void createSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
