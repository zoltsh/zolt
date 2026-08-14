package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

abstract class PackagePlanCommandTestSupport {
    protected static void writePackagePlanLockfile(
            Path projectDir,
            boolean includePolicy,
            boolean includeSuspiciousContainerRuntime) throws IOException {
        String policy = includePolicy
                ? """
                policies = ["strict-version: com.example:runtime-lib -> 1.0.0 (security baseline)"]
                """
                : "";
        String suspiciousContainer = includeSuspiciousContainerRuntime
                ? """

                [[package]]
                id = "org.apache.tomcat.embed:tomcat-embed-core"
                version = "10.1.40"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar"
                dependencies = []
                """
                : "";
        write(projectDir.resolve("zolt.lock"), projectDir.resolve(".zolt/cache"), """
                version = 1

                [[package]]
                id = "org.springframework.boot:spring-boot"
                version = "4.0.6"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar"
                dependencies = []

                [[package]]
                id = "org.springframework.boot:spring-boot-loader"
                version = "4.0.6"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar"
                dependencies = []

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []
                %s

                [[package]]
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:devtools"
                version = "1.0.0"
                source = "maven-central"
                scope = "dev"
                direct = true
                jar = "com/example/devtools/1.0.0/devtools-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:test-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "com/example/test-lib/1.0.0/test-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                %s
                """.formatted(policy, suspiciousContainer));
    }

    protected static void writePackagePlanProject(Path projectDir, String name, String body) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig(name) + body);
    }
}
