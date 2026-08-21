package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;

/**
 * Manifest pairs that mean the same thing in the legacy dialect and in the final language, asserted
 * to produce the same legacy {@link ProjectConfig}.
 *
 * <p>The legacy half of every pair goes through {@link LegacyManifestDialect}, the one helper the
 * cleanup phase deletes with {@link sh.zolt.toml.ZoltTomlParser}. At that point each pair keeps only
 * its final source and asserts against the golden canonical fixture instead.
 */
final class ManifestProjectConfigEquivalenceTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @Test
    void minimalApplicationPairIsEquivalent() {
        assertEquivalent(
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
        ProjectConfig adapted = assertEquivalent(
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
        ProjectConfig adapted = assertEquivalent(
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
        assertEquivalent(
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
    void buildCompilerResourcesAndTestsPairIsEquivalent() {
        assertEquivalent(
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
        ProjectConfig adapted = loader.load(
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
                java.util.List.of("src/extra/java", "src/main/java"),
                adapted.build().sourceRoots(),
                "design §5.5 makes semantically unordered path arrays canonically sorted");
        assertEquals("src/extra/java", adapted.build().source());
    }

    @Test
    void springBootPackagingPairIsEquivalent() {
        assertEquivalent(
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
        assertEquivalent(
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
        assertEquivalent(
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
    void generatedToolsAndStepsPairIsEquivalent() {
        assertEquivalent(
                """
                [project]
                name = "codegen"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [versions]
                jooq = "3.19.0"

                [generated.openapiTool]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.protobufTool]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.29.3"
                grpcPluginCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcPluginVersion = "1.69.0"

                [generated.openapiPresets.spring-client]
                generator = "java"
                library = "webclient"
                apiPackage = "com.example.api"
                configOptions = { useJakartaEe = "true" }

                [generated.execTools.jooq]
                runner = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.execTools.node]
                runner = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                versionExpect = ">=10 <11"
                allowUnpinnedTool = true

                [generated.main.assets]
                kind = "exec"
                language = "java"
                tool = "node"
                inputs = ["src/main/web"]
                output = "target/generated/resources/assets"
                produces = "resources"
                into = "static"

                [generated.main.jooq-model]
                kind = "exec"
                language = "java"
                tool = "jooq"
                args = ["src/main/jooq/config.xml"]
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/jooq"
                produces = "java-sources"
                cache = "none"
                cwd = "tools"
                env = { NODE_ENV = "production" }
                inheritEnv = ["HTTP_PROXY"]
                timeoutSeconds = 900

                [generated.main.protocol]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/service.proto"]
                output = "target/generated/sources/protocol"
                javaPackage = "com.example.protocol"
                grpc = false

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/public-api"
                preset = "spring-client"
                modelPackage = "com.example.model"
                validateSpec = false

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["src/test/fixtures"]
                output = "target/generated/test-sources/fixtures"
                required = false
                clean = true
                """,
                """
                [project]
                name = "codegen"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.0"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.29.3"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersion = "1.69.0"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.tools.node]
                kind = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                versionExpect = ">=10 <11"
                allowUnpinnedTool = true

                [generated.presets.spring-client]
                kind = "openapi"
                generator = "java"
                library = "webclient"
                apiPackage = "com.example.api"
                configOptions = { useJakartaEe = "true" }

                [generated.main.public-api]
                kind = "openapi"
                input = "src/main/openapi/public-api.yaml"
                preset = "spring-client"
                modelPackage = "com.example.model"
                validateSpec = false

                [generated.main.protocol]
                kind = "protobuf"
                inputs = ["src/main/proto/service.proto"]
                javaPackage = "com.example.protocol"
                grpc = false

                [generated.main.jooq-model]
                kind = "exec"
                tool = "jooq"
                args = ["src/main/jooq/config.xml"]
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/jooq"
                produces = "java-sources"
                cache = "none"
                cwd = "tools"
                env = { NODE_ENV = "production" }
                inheritEnv = ["HTTP_PROXY"]
                timeoutSeconds = 900

                [generated.main.assets]
                kind = "exec"
                tool = "node"
                inputs = ["src/main/web"]
                output = "target/generated/resources/assets"
                produces = "resources"
                into = "static"

                [generated.test.fixtures]
                kind = "declared-root"
                inputs = ["src/test/fixtures"]
                output = "target/generated/test-sources/fixtures"
                required = false
                clean = true
                """);
    }

    @Test
    void centralReadyLibraryPairIsEquivalent() {
        assertEquivalent(
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
        ProjectConfig adapted = loader.load(
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

    @Test
    void tasksAndAliasesDoNotPerturbTheProjectConfig() {
        assertEquivalent(
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

    @Test
    void coverageFloorsPairIsEquivalent() {
        CoverageSettings legacy = LegacyManifestDialect.coverageFloors(
                """
                [project]
                name = "covered"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [coverage]
                minLine = 88
                minBranch = 74
                minInstruction = 80
                minMethod = 85
                """);
        CoverageSettings adapted = loader.coverageFloors(
                """
                [project]
                name = "covered"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [coverage]
                line = 88
                branch = 74
                instruction = 80
                method = 85
                """);
        assertEquals(legacy, adapted);
        assertEquals(Optional.of(88.0), adapted.minLine());
    }

    @Test
    void absentCoverageSectionHasNoFloors() {
        assertEquals(
                CoverageSettings.none(),
                loader.coverageFloors(
                        """
                        [project]
                        name = "uncovered"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21
                        """));
    }

    @Test
    void derivedGeneratedOutputsFollowTheBuildOutputRoot() {
        ProjectConfig adapted = loader.load(
                """
                [project]
                name = "derived"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [build.output]
                root = "build"

                [generated.main.public-api]
                kind = "openapi"
                input = "src/main/openapi/public-api.yaml"

                [generated.test.fixtures-api]
                kind = "openapi"
                input = "src/test/openapi/fixtures.yaml"
                """);
        assertEquals(
                "build/generated/sources/public-api",
                adapted.build().generatedMainSources().getFirst().output(),
                "design §13.4 derives the main output from [build.output].root");
        assertEquals(
                "build/generated/test-sources/fixtures-api",
                adapted.build().generatedTestSources().getFirst().output(),
                "design §13.4 derives the test output from [build.output].root");
        assertEquals("build/generated/sources/annotations", adapted.compilerSettings().generatedSources());
        assertEquals("build/native", adapted.nativeSettings().output());
    }

    @Test
    void generatedStepsAreOrderedByStepId() {
        ProjectConfig adapted = loader.load(
                """
                [project]
                name = "ordered"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [generated.main.zeta]
                kind = "declared-root"
                inputs = ["src/main/zeta"]
                output = "target/generated/sources/zeta"

                [generated.main.alpha]
                kind = "declared-root"
                inputs = ["src/main/alpha"]
                output = "target/generated/sources/alpha"
                """);
        assertEquals(
                java.util.List.of("alpha", "zeta"),
                adapted.build().generatedMainSources().stream()
                        .map(sh.zolt.project.GeneratedSourceStep::id)
                        .toList(),
                "design §5.6 and §13.9 make step order derived, not authored");
    }

    @Test
    void legacyDialectSpellingsAreRejectedWithoutCompatibilityHints() {
        assertPlainRejection(
                """
                [project]
                name = "legacy"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [api.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"
                """);
        assertPlainRejection(
                """
                [project]
                name = "legacy"
                version = "1.0.0"
                group = "com.example"
                java = "21"
                """);
        assertPlainRejection(
                """
                [project]
                name = "legacy"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [package]
                mode = "thin"
                """);
    }

    @Test
    void workspaceSelectorsAreRejectedInAStandaloneManifest() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> loader.load(
                        """
                        [project]
                        name = "standalone"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21

                        [dependencies]
                        "com.example:core" = { workspace = true }
                        """));
        assertTrue(
                failure.getMessage().contains("workspace"),
                () -> "expected a workspace diagnostic, got: " + failure.getMessage());
    }

    private void assertPlainRejection(String finalSource) {
        ZoltConfigException failure =
                assertThrows(ZoltConfigException.class, () -> loader.load(finalSource));
        String message = failure.getMessage();
        assertFalse(
                message.contains("legacy") || message.contains("migrat") || message.contains("rename"),
                () -> "design §21 Phase 2 forbids compatibility hints, got: " + message);
    }

    @Test
    void everyStandaloneGoldenLoadsThroughTheFinalBoundary() throws IOException {
        assertEquals("hello", golden("standalone-application.toml").project().name());
        ProjectConfig library = golden("library-api-boundary.toml");
        assertEquals("2.0.17", library.apiDependencies().get("org.slf4j:slf4j-api"));
        assertEquals(
                "2.19.0", library.dependencies().get("com.fasterxml.jackson.core:jackson-databind"));
        ProjectConfig springBoot = golden("spring-boot-service.toml");
        assertEquals(sh.zolt.project.PackageMode.SPRING_BOOT, springBoot.packageSettings().mode());
        assertEquals(
                "4.0.6",
                springBoot.platforms().get("org.springframework.boot:spring-boot-dependencies"));
        assertTrue(springBoot.managedDependencies()
                .contains("org.springframework.boot:spring-boot-starter-webmvc"));
        ProjectConfig central = golden("central-ready-library.toml");
        assertEquals("Apache-2.0", central.packageSettings().metadata().license());
        assertEquals(
                "https://github.com/example/library",
                central.packageSettings().metadata().scm());
        assertTrue(central.packageSettings().sources());
        ProjectConfig enterprise = golden("enterprise-repository.toml");
        assertEquals(
                "https://repo.example.com/maven", enterprise.repositories().get("company"));
        assertEquals(
                "MAVEN_USERNAME",
                enterprise.repositoryCredentials().get("company").usernameEnv().orElseThrow());
    }

    private ProjectConfig golden(String resourceName) throws IOException {
        try (java.io.InputStream stream =
                getClass().getResourceAsStream("/golden/manifest-language/" + resourceName)) {
            return loader.load(new String(
                    java.util.Objects.requireNonNull(stream, resourceName).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private ProjectConfig assertEquivalent(String legacySource, String finalSource) {
        ProjectConfig legacy = LegacyManifestDialect.parse(legacySource);
        ProjectConfig adapted = loader.load(finalSource);
        ProjectConfigEquivalence.assertEquivalent(legacy, adapted);
        return adapted;
    }
}
