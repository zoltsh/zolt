package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PolicyCommandTestSupport {
    private PolicyCommandTestSupport() {}

    /** The same project as a workspace member: the root owns the repositories (design 8.7). */
    static void writePolicyMember(Path memberDir) throws IOException {
        writePolicyProject(memberDir);
        Files.writeString(memberDir.resolve("zolt.toml"),
                Files.readString(memberDir.resolve("zolt.toml")).replace("""
                        [repositories]
                        central = false

                        [repositories.test]
                        url = "https://repo.maven.apache.org/maven2"

                        """.replace("                        ", "                "), ""));
    }

    static void writePolicyProject(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """

                [repositories]
                central = false

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"

                [versions]
                "spring-boot" = "4.0.6"
                "direct-lib" = "1.2.3"
                "tomcat-baseline" = "10.1.40"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring-boot" }

                [dependencies]
                "com.example:direct-lib" = { versionRef = "direct-lib" }
                "org.springframework.boot:spring-boot-starter-web" = { managed = true }

                [dependencies.policy]
                deny = [
                  { coordinate = "com.example:direct-lib", reason = "Direct dependency conflict fixture" },
                  { coordinate = "commons-logging:commons-logging", reason = "Use jcl-over-slf4j" },
                  { coordinate = "log4j:log4j", reason = "Legacy logging baseline" }
                ]

                [dependencies.constraints]
                "com.example:unused" = { version = "1.0.0", reason = "Unused baseline" }
                "org.apache.tomcat.embed:tomcat-embed-core" = { versionRef = "tomcat-baseline", reason = "Container baseline" }
                """);
    }

    static void writePolicyLockfile(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:direct-lib"
                version = "1.2.3"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "4.0.6"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:direct-lib"
                version = "1.2.3"
                source = "maven-central"
                scope = "compile"
                direct = true
                members = ["apps/api"]
                policies = ["version-ref: com.example:direct-lib -> 1.2.3 from [versions].direct-lib"]
                dependencies = []

                [[package]]
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "4.0.6"
                source = "maven-central"
                scope = "compile"
                direct = true
                members = ["apps/api"]
                dependencies = ["org.apache.tomcat.embed:tomcat-embed-core:10.1.40"]
                policies = ["managed-version: org.springframework.boot:spring-boot-starter-web -> 4.0.6 from org.springframework.boot:spring-boot-dependencies:4.0.6"]

                [[package]]
                id = "org.apache.tomcat.embed:tomcat-embed-core"
                version = "10.1.40"
                source = "maven-central"
                scope = "runtime"
                direct = false
                members = ["apps/api"]
                dependencies = []
                policies = ["strict-version: org.apache.tomcat.embed:tomcat-embed-core requested 10.1.39 -> 10.1.40 (Container baseline)"]

                [[policy]]
                kind = "strict-version"
                id = "org.apache.tomcat.embed:tomcat-embed-core"
                requested = "10.1.39"
                source = "org.springframework.boot:spring-boot-starter-web:4.0.6"
                policy = "strict-version: org.apache.tomcat.embed:tomcat-embed-core requested 10.1.39 -> 10.1.40 (Container baseline)"

                [[policy]]
                kind = "global-exclusion"
                id = "commons-logging:commons-logging"
                requested = "1.2"
                source = "org.springframework.boot:spring-boot-starter-web:4.0.6"
                policy = "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)"
                """);
    }
}
