package sh.zolt.quality.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.BuildResult;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckStatus;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

abstract class PackageQualityCheckTestSupport {
    protected final PackageQualityCheck check = new PackageQualityCheck(
            new PackagePlanService(),
            new PackageEvidenceManifestReader());
    private final ZoltTomlParser parser = new ZoltTomlParser();

    protected ProjectConfig parseProject(Path projectDir, String body) throws IOException {
        Files.createDirectories(projectDir);
        Path config = projectDir.resolve("zolt.toml");
        Files.writeString(config, memberConfig(projectDir.getFileName().toString()) + body);
        return parser.parse(config);
    }

    protected static String packageMetadata() {
        return """

                [package.metadata]
                name = "Example Library"
                description = "Small Java helpers."
                url = "https://example.com/library"
                license = "Apache-2.0"
                developers = ["Example Team"]
                scm = "https://example.com/library.git"
                issues = "https://example.com/library/issues"
                """;
    }

    protected static void writeLockfile(Path projectDir, String packages) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n" + packages);
    }

    protected static void writeCurrentPackageEvidence(
            Path projectDir,
            ProjectConfig config,
            List<PackageArtifact> artifacts) throws IOException {
        PackagePlan plan = new PackagePlanService().plan(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"));
        if (plan.runtimeClasspathPath().isPresent()) {
            Files.writeString(
                    plan.runtimeClasspathPath().orElseThrow(),
                    "");
        }
        PackageResult result = new PackageResult(
                new BuildResult(
                        Optional.empty(),
                        0,
                        0,
                        projectDir.resolve("target/classes"),
                        ""),
                plan.mode(),
                plan.archivePath(),
                plan.runtimeClasspathPath(),
                Optional.empty(),
                1,
                config.project().main().isPresent(),
                plan.applicationLayout(),
                artifacts,
                List.of());
        new PackageEvidenceManifestWriter().write(
                projectDir,
                config,
                plan,
                result,
                artifacts);
    }

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
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "."
                id = "com.example:devtools"
                version = "1.0.0"
                lane = "dev"
                resolvedScope = "dev"

                [[dependencyRoot]]
                member = "."
                id = "com.example:test-lib"
                version = "1.0.0"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "."
                id = "com.example:processor"
                version = "1.0.0"
                lane = "processor"
                resolvedScope = "processor"

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

    protected static void assertResult(
            QualityCheckResult result,
            String id,
            QualityCheckStatus status,
            String subject,
            String message,
            String nextStep) {
        assertEquals(id, result.id());
        assertEquals(status, result.status());
        assertEquals(subject, result.subject());
        assertEquals(message, result.message());
        assertEquals(nextStep, result.nextStep());
    }

    private static String memberConfig(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """.formatted(name);
    }
}
