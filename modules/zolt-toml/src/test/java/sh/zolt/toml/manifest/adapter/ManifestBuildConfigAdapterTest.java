package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.project.BomSettings;
import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.UberDuplicatePolicy;

/**
 * Build layout, compiler, resource, test, and packaging manifests asserted to reach the expected
 * {@link ProjectConfig} through the final boundary.
 */
final class ManifestBuildConfigAdapterTest {
    @Test
    void buildCompilerResourcesAndTestsReachTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
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

        assertEquals(List.of("src/extra/java", "src/main/java"), adapted.build().sourceRoots());
        assertEquals("build", adapted.build().outputRoot());
        assertEquals("build/classes", adapted.build().output());
        assertEquals("build/test-classes", adapted.build().testOutput());
        assertTrue(adapted.build().metadata().buildInfo());
        assertTrue(adapted.build().metadata().git());
        assertTrue(adapted.build().metadata().reproducible());

        assertEquals("UTF-8", adapted.compilerSettings().encoding());
        assertEquals(List.of("-Xlint:all"), adapted.compilerSettings().args());
        assertEquals(List.of("-parameters"), adapted.compilerSettings().testArgs());
        assertEquals("host", adapted.compilerSettings().platformApi());
        assertEquals("release", adapted.compilerSettings().testPlatformApi());
        assertEquals(
                "build/generated/sources/annotations", adapted.compilerSettings().generatedSources());
        assertEquals(
                "build/generated/test-sources/annotations",
                adapted.compilerSettings().generatedTestSources());

        assertEquals(
                List.of("src/extra/resources", "src/main/resources"), adapted.build().resourceRoots());
        assertEquals(List.of("src/test/resources"), adapted.build().testResourceRoots());
        assertTrue(adapted.build().resourceFiltering().enabled());
        assertTrue(adapted.build().resourceFiltering().testEnabled());
        assertEquals(
                List.of("**/*.properties", "**/*.yaml"),
                adapted.build().resourceFiltering().includes());
        assertEquals(
                ResourceMissingTokenPolicy.KEEP, adapted.build().resourceFiltering().missing());
        assertEquals(
                List.of("app-version", "build-id", "channel"),
                List.copyOf(adapted.build().resourceFiltering().tokens().keySet()));

        assertEquals(List.of("src/it/java"), adapted.build().integrationTestSources());
        assertEquals(List.of("src/it/resources"), adapted.build().integrationTestResourceRoots());
        assertEquals(
                "build/integration-test-classes",
                adapted.build().integrationTestOutput(),
                "design §13.4 derives the integration output from [build.output].root");

        assertEquals(List.of("-Xmx2g"), adapted.build().testRuntime().jvmArgs());
        assertEquals(Map.of("user.timezone", "UTC"), adapted.build().testRuntime().systemProperties());
        assertEquals(Map.of("APP_ENV", "test"), adapted.build().testRuntime().environment());
        assertEquals(List.of("skipped", "failed"), adapted.build().testRuntime().events());
        assertEquals(List.of("src/test/java"), adapted.build().testSources());
        assertEquals(List.of("src/test/groovy"), adapted.build().groovyTestSources());

        assertEquals(List.of("smoke"), List.copyOf(adapted.build().testSuites().keySet()));
        assertEquals(
                List.of("*SmokeTest"), adapted.build().testSuites().get("smoke").includeClassname());
        assertEquals(
                List.of("*FlakySmokeTest"),
                adapted.build().testSuites().get("smoke").excludeClassname());
        assertEquals(List.of("smoke"), adapted.build().testSuites().get("smoke").includeTag());
        assertEquals(List.of("slow"), adapted.build().testSuites().get("smoke").excludeTag());
        assertTrue(adapted.build().testSuites().get("smoke").parallelSafe());
        assertEquals(4, adapted.build().testSuites().get("smoke").maxWorkers());
        assertEquals(
                Map.of("com.example.DatabaseSmokeTest", List.of("database")),
                adapted.build().testSuites().get("smoke").resourceLocks());
    }

    @Test
    void multiRootArraysKeepAuthoredOrderAndTheirFirstEntryStaysPrimary() {
        ProjectConfig adapted = FinalManifests.load(
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
                List.of("src/main/java", "src/extra/java"),
                adapted.build().sourceRoots(),
                "design §5.5 keeps order-bearing path arrays in authored order");
        assertEquals("src/main/java", adapted.build().source());
    }

    @Test
    void springBootNativePackagingReachesTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
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

        assertEquals(PackageMode.SPRING_BOOT, adapted.packageSettings().mode());
        assertEquals(
                Map.of("Automatic-Module-Name", "com.example.orders"),
                adapted.packageSettings().manifestAttributes());
        assertTrue(adapted.frameworkSettings().springBoot().nativeEnabled());
        assertEquals(
                "orders-api",
                adapted.nativeSettings().imageName(),
                "design §11.3 derives the image name from the project name");
        assertEquals("target/native", adapted.nativeSettings().output());
        assertEquals(List.of("--no-fallback"), adapted.nativeSettings().args());
    }

    @Test
    void quarkusPackagingImpliesTheQuarkusFramework() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "inventory"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [package]
                mode = "quarkus"
                """);

        assertEquals(PackageMode.QUARKUS, adapted.packageSettings().mode());
        assertTrue(
                adapted.frameworkSettings().quarkus().enabled(),
                "design §11.2 derives framework activation from the package mode");
        assertEquals(QuarkusPackageMode.FAST_JAR, adapted.frameworkSettings().quarkus().packageMode());
    }

    @Test
    void uberJarPackagingReachesTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
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

        assertEquals(PackageMode.UBER, adapted.packageSettings().mode());
        assertTrue(adapted.packageSettings().sources());
        assertTrue(adapted.packageSettings().javadoc());
        assertTrue(adapted.packageSettings().tests());
        assertEquals(UberDuplicatePolicy.FIRST_WINS, adapted.packageSettings().uberDuplicates());
    }

    /** An omitted {@code duplicates} keeps the strict default: a colliding uber-jar entry fails. */
    @Test
    void uberJarWithoutADuplicatePolicyDefaultsToFail() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "tool"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [package]
                mode = "uber-jar"
                """);

        assertEquals(PackageMode.UBER, adapted.packageSettings().mode());
        assertEquals(UberDuplicatePolicy.FAIL, adapted.packageSettings().uberDuplicates());
    }

    @Test
    void centralReadyLibraryPublicationMetadataReachesTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
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

        assertEquals("A reusable Java library.", adapted.packageSettings().metadata().description());
        assertEquals("https://example.com/library", adapted.packageSettings().metadata().url());
        assertEquals("Apache-2.0", adapted.packageSettings().metadata().license());
        assertEquals(
                "https://spdx.org/licenses/Apache-2.0.html",
                adapted.packageSettings().metadata().licenseUrl(),
                "design §7.3 derives the license URL from the SPDX identifier");
        assertEquals(
                "https://github.com/example/library", adapted.packageSettings().metadata().scm());
        assertEquals(
                "scm:git:https://github.com/example/library.git",
                adapted.packageSettings().metadata().scmConnection());
        assertEquals(
                "scm:git:ssh://git@github.com/example/library.git",
                adapted.packageSettings().metadata().scmDeveloperConnection());
        assertEquals("v1.0.0", adapted.packageSettings().metadata().scmTag());
        assertEquals(
                "https://github.com/example/library/issues",
                adapted.packageSettings().metadata().issues());
        assertEquals(
                List.of("maintainer"),
                adapted.packageSettings().metadata().developerEntries().stream()
                        .map(DeveloperEntry::id)
                        .toList());
        assertEquals(
                "Example Maintainer",
                adapted.packageSettings().metadata().developerEntries().getFirst().name());
        assertTrue(adapted.packageSettings().sources());
        assertTrue(adapted.packageSettings().javadoc());
    }

    @Test
    void standaloneBomHasNoProjectJavaRelease() {
        ProjectConfig adapted = FinalManifests.load(
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

        assertEquals(
                "",
                adapted.project().java(),
                "design §12.6 forbids project.java on a BOM and forbids inheriting it");
        assertEquals(PackageMode.BOM, adapted.packageSettings().mode());
        assertEquals(
                List.of("org.postgresql:postgresql"),
                adapted.packageSettings().bom().versions().stream()
                        .map(BomSettings.ManagedVersion::coordinate)
                        .toList());
        assertEquals(
                "42.7.4", adapted.packageSettings().bom().versions().getFirst().version());
        assertEquals(
                List.of("com.fasterxml.jackson:jackson-bom"),
                adapted.packageSettings().bom().imports().stream()
                        .map(BomSettings.ImportedBom::coordinate)
                        .toList());
        assertEquals("jackson", adapted.packageSettings().bom().imports().getFirst().versionRef());
    }
}
