package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.project.ProjectConfig;

/**
 * Build layout, compiler, resource, test, and packaging manifest pairs asserted to produce the same
 * legacy {@link sh.zolt.project.ProjectConfig}.
 */
final class ManifestBuildConfigEquivalenceTest {
    @Test
    void buildCompilerResourcesAndTestsPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "layout"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [build]
                sources = ["src/extra/java", "src/main/java"]
                test = "src/test/java"
                outputRoot = "build"
                output = "build/classes"
                testOutput = "build/test-classes"

                [build.metadata]
                buildInfo = true
                git = true
                reproducible = true

                [compiler]
                encoding = "UTF-8"
                args = ["-Xlint:all"]
                testArgs = ["-parameters"]
                platformApi = "host"
                testPlatformApi = "release"
                generatedSources = "build/generated/sources/annotations"
                generatedTestSources = "build/generated/test-sources/annotations"

                [resources]
                main = ["src/extra/resources", "src/main/resources"]
                test = ["src/test/resources"]

                [resources.filtering]
                enabled = true
                test = true
                includes = ["**/*.properties", "**/*.yaml"]
                missing = "keep"

                [resources.tokens]
                app-version = { project = "version" }
                build-id = { env = "BUILD_ID" }
                channel = { value = "preview" }

                [integrationTest]
                sources = ["src/it/java"]
                resources = ["src/it/resources"]
                output = "build/integration-test-classes"

                [test.sources]
                java = ["src/test/java"]
                groovy = ["src/test/groovy"]

                [test.runtime]
                jvmArgs = ["-Xmx2g"]
                systemProperties = { "user.timezone" = "UTC" }
                environment = { APP_ENV = "test" }
                events = ["skipped", "failed"]

                [test.suites.smoke]
                includeClassname = ["*SmokeTest"]
                excludeClassname = ["*FlakySmokeTest"]
                includeTag = ["smoke"]
                excludeTag = ["slow"]
                parallelSafe = true
                maxWorkers = 4
                resourceLocks = { "com.example.DatabaseSmokeTest" = ["database"] }
                """,
                """
                [project]
                name = "layout"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/extra/java", "src/main/java"]

                [build.output]
                root = "build"

                [build.metadata]
                buildInfo = true
                git = true
                reproducible = true

                [compiler]
                encoding = "UTF-8"
                jdkApi = "host"
                args = ["-Xlint:all"]

                [compiler.test]
                jdkApi = "release"
                args = ["-parameters"]

                [resources]
                main = ["src/extra/resources", "src/main/resources"]
                test = ["src/test/resources"]

                [resources.filter]
                targets = ["main", "test"]
                include = ["**/*.properties", "**/*.yaml"]
                missing = "keep"

                [resources.tokens]
                app-version = { project = "version" }
                build-id = { env = "BUILD_ID" }
                channel = { value = "preview" }

                [test.sources]
                java = ["src/test/java"]
                groovy = ["src/test/groovy"]

                [test.runtime]
                jvmArgs = ["-Xmx2g"]
                properties = { "user.timezone" = "UTC" }
                env = { APP_ENV = "test" }
                events = ["skipped", "failed"]

                [test.integration]
                sources = ["src/it/java"]
                resources = ["src/it/resources"]

                [test.suites.smoke]
                classes = ["*SmokeTest"]
                excludeClasses = ["*FlakySmokeTest"]
                tags = ["smoke"]
                excludeTags = ["slow"]
                workers = 4
                locks = [{ class = "com.example.DatabaseSmokeTest", resources = ["database"] }]
                """);
    }

    @Test
    void multiRootArraysAreCanonicallySorted() {
        ProjectConfig adapted = FinalManifestPairs.loader().load(
                """
                [project]
                name = "roots"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "src/extra/java"]
                """);
        assertEquals(
                List.of("src/extra/java", "src/main/java"),
                adapted.build().sourceRoots(),
                "design §5.5 makes semantically unordered path arrays canonically sorted");
        assertEquals("src/extra/java", adapted.build().source());
    }

    @Test
    void springBootPackagingPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "orders-api"
                version = "0.1.0"
                group = "com.example.orders"
                java = "21"
                main = "com.example.orders.Application"

                [package]
                mode = "spring-boot"

                [package.manifest]
                "Automatic-Module-Name" = "com.example.orders"

                [framework.springBoot.native]
                enabled = true

                [native]
                imageName = "orders-api"
                output = "target/native"
                args = ["--no-fallback"]
                """,
                """
                [project]
                name = "orders-api"
                version = "0.1.0"
                group = "com.example.orders"
                java = 21
                main = "com.example.orders.Application"

                [package]
                mode = "spring-boot"

                [package.manifest]
                "Automatic-Module-Name" = "com.example.orders"

                [framework.spring-boot]
                native = true

                [native]
                args = ["--no-fallback"]
                """);
    }

    @Test
    void quarkusPackagingPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "inventory"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [package]
                mode = "quarkus"

                [framework.quarkus]
                enabled = true
                package = "fast-jar"
                """,
                """
                [project]
                name = "inventory"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [package]
                mode = "quarkus"
                """);
    }

    @Test
    void uberJarPackagingPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "tool"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [package]
                mode = "uber"
                sources = true
                javadoc = true
                tests = true
                uberDuplicates = "first-wins"
                """,
                """
                [project]
                name = "tool"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [package]
                mode = "uber-jar"
                sources = true
                javadoc = true
                testJar = true
                duplicates = "first-wins"
                """);
    }

    @Test
    void centralReadyLibraryPairIsEquivalent() {
        FinalManifestPairs.assertEquivalent(
                """
                [project]
                name = "example-library"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [package]
                sources = true
                javadoc = true

                [package.metadata]
                description = "A reusable Java library."
                url = "https://example.com/library"
                license = "Apache-2.0"
                scm = "https://github.com/example/library"
                scmConnection = "scm:git:https://github.com/example/library.git"
                scmDeveloperConnection = "scm:git:ssh://git@github.com/example/library.git"
                scmTag = "v1.0.0"
                issues = "https://github.com/example/library/issues"

                [package.metadata.developer.maintainer]
                name = "Example Maintainer"
                email = "maintainer@example.com"
                """,
                """
                [project]
                name = "example-library"
                version = "1.0.0"
                group = "com.example"
                java = 21
                description = "A reusable Java library."
                url = "https://example.com/library"
                issues = "https://github.com/example/library/issues"
                license = "Apache-2.0"

                [project.scm]
                url = "https://github.com/example/library"
                connection = "scm:git:https://github.com/example/library.git"
                developerConnection = "scm:git:ssh://git@github.com/example/library.git"
                tag = "v1.0.0"

                [project.developers.maintainer]
                name = "Example Maintainer"
                email = "maintainer@example.com"

                [package]
                sources = true
                javadoc = true

                [publish.signing]
                method = "gpg"
                keyId = "3AB1C2D3E4F5A6B7"
                passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"

                [publish.central]
                tokenEnv = "ZOLT_CENTRAL_TOKEN"
                mode = "automatic"
                """);
    }

    @Test
    void standaloneBomPairIsEquivalent() {
        ProjectConfig legacy = LegacyManifestDialect.parse(
                """
                [project]
                name = "platform-bom"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [versions]
                jackson = "2.19.0"

                [bom.versions]
                "org.postgresql:postgresql" = "42.7.4"

                [bom.imports]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }
                """);
        ProjectConfig adapted = FinalManifestPairs.loader().load(
                """
                [project]
                name = "platform-bom"
                version = "1.0.0"
                group = "com.example"

                [versions]
                jackson = "2.19.0"

                [bom.versions]
                "org.postgresql:postgresql" = "42.7.4"

                [bom.imports]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }
                """);
        ProjectConfigEquivalence.assertBomEquivalent(legacy, adapted);
    }
}
