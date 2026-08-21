package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sh.zolt.project.ProjectConfig;

/**
 * Identity, dependency-lane, and shared-configuration manifest pairs asserted to produce the same
 * legacy {@link ProjectConfig}.
 *
 * <p>The legacy half of every pair goes through {@link LegacyManifestDialect}, the one helper the
 * cleanup phase deletes with {@link sh.zolt.toml.ZoltTomlParser}. At that point each pair keeps only
 * its final source and asserts against the golden canonical fixture instead.
 */
final class ManifestProjectConfigEquivalenceTest {
    @Test
    void minimalApplicationPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "hello"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [test.dependencies]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """,
                """
                [project]
                name = "hello"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """);
    }

    @Test
    void everyDependencyLanePairIsEquivalent() {
        ProjectConfig adapted = FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "lanes"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [versions]
                junit = "5.13.4"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "4.0.6"

                [api.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"

                [dependencies]
                "com.fasterxml.jackson.core:jackson-databind" = "2.19.0"
                "org.springframework.boot:spring-boot-starter-webmvc" = {}

                [runtime.dependencies]
                "ch.qos.logback:logback-classic" = "1.5.18"

                [provided.dependencies]
                "jakarta.servlet:jakarta.servlet-api" = "6.1.0"

                [dev.dependencies]
                "org.springframework.boot:spring-boot-devtools" = {}

                [annotationProcessors]
                "org.mapstruct:mapstruct-processor" = "1.6.3"

                [test.dependencies]
                "org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }

                [test.annotationProcessors]
                "org.projectlombok:lombok" = "1.18.38"
                """,
                """
                [project]
                name = "lanes"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [versions]
                junit = "5.13.4"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "4.0.6"

                [dependencies]
                "com.fasterxml.jackson.core:jackson-databind" = "2.19.0"
                "org.springframework.boot:spring-boot-starter-webmvc" = { managed = true }

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.17"

                [dependencies.runtime]
                "ch.qos.logback:logback-classic" = "1.5.18"

                [dependencies.provided]
                "jakarta.servlet:jakarta.servlet-api" = "6.1.0"

                [dependencies.dev]
                "org.springframework.boot:spring-boot-devtools" = { managed = true }

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }

                [dependencies.processor]
                "org.mapstruct:mapstruct-processor" = "1.6.3"

                [dependencies.test-processor]
                "org.projectlombok:lombok" = "1.18.38"
                """);
        assertEquals("5.13.4", adapted.testDependencies().get("org.junit.jupiter:junit-jupiter"));
        assertTrue(adapted.managedDependencies()
                .contains("org.springframework.boot:spring-boot-starter-webmvc"));
        assertTrue(adapted.managedDevDependencies()
                .contains("org.springframework.boot:spring-boot-devtools"));
    }

    @Test
    void dependencyMetadataPairIsEquivalent() {
        ProjectConfig adapted = FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "metadata"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [versions]
                netty = "4.1.119.Final"

                [api.dependencies]
                "com.example:client" = { version = "1.4.0", optional = true, exclusions = [{ group = "commons-logging", artifact = "commons-logging" }] }

                [dependencies]
                "io.netty:netty-handler" = { versionRef = "netty", classifier = "linux-x86_64", type = "jar" }
                "com.example:publish-only" = { version = "2.0.0", publishOnly = true }
                """,
                """
                [project]
                name = "metadata"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [versions]
                netty = "4.1.119.Final"

                [dependencies]
                "io.netty:netty-handler" = { versionRef = "netty", classifier = "linux-x86_64", type = "jar" }
                "com.example:publish-only" = { version = "2.0.0", publishOnly = true }

                [dependencies.api]
                "com.example:client" = { version = "1.4.0", optional = true, exclude = ["commons-logging:commons-logging"] }
                """);
        assertTrue(adapted.dependencies().containsKey("io.netty:netty-handler"));
        assertTrue(!adapted.dependencies().containsKey("com.example:publish-only"),
                "publish-only dependencies stay out of the resolved lane map");
    }

    @Test
    void enterpriseRepositoryPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "enterprise-library"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"
                company = { url = "https://repo.example.com/maven", credentials = "company" }

                [repositoryCredentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [repositoryCredentials.github]
                tokenEnv = "GITHUB_TOKEN"

                [versions]
                jackson = "2.19.0"

                [platforms]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }

                [dependencyPolicy]
                failOnVersionConflict = true
                exclude = [{ group = "commons-logging", artifact = "commons-logging", reason = "Use SLF4J" }]

                [dependencyPolicy.licenses]
                allow = ["Apache-2.0", "MIT"]
                deny = ["GPL-3.0-only"]
                unknown = "fail"

                [dependencyPolicy.licenses.exceptions."org.example:matchit"]
                allow = ["BSD-3-Clause"]
                version = "0.8.4"
                reason = "Reviewed transitive dependency"

                [dependencyConstraints]
                "io.netty:netty-handler" = { version = "4.1.119.Final", kind = "strict", reason = "Security baseline" }
                """,
                """
                [project]
                name = "enterprise-library"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [repositories.company]
                url = "https://repo.example.com/maven"
                credentials = "company"

                [credentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [credentials.github]
                tokenEnv = "GITHUB_TOKEN"

                [versions]
                jackson = "2.19.0"

                [platforms]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }

                [dependencies.constraints]
                "io.netty:netty-handler" = { version = "4.1.119.Final", reason = "Security baseline" }

                [dependencies.policy]
                conflicts = "fail"
                deny = [{ coordinate = "commons-logging:commons-logging", reason = "Use SLF4J" }]

                [dependencies.policy.licenses]
                allow = ["Apache-2.0", "MIT"]
                deny = ["GPL-3.0-only"]
                unknown = "fail"

                [dependencies.license-exceptions."org.example:matchit"]
                allow = ["BSD-3-Clause"]
                version = "0.8.4"
                reason = "Reviewed transitive dependency"
                """);
    }

    @Test
    void tasksAndAliasesDoNotPerturbTheProjectConfig() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "commands"
                version = "1.0.0"
                group = "com.example"
                java = "21"
                """,
                """
                [project]
                name = "commands"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [tasks.release-notes]
                description = "Generate release notes"
                run = ["zolt", "run", "--", "release-notes"]

                [aliases]
                ci = ["check", "--all"]
                """);
    }
}
