package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ZoltTomlParserTest {
    private final ZoltTomlParser parser = new ZoltTomlParser();

    @Test
    void parsesValidExampleConfig() {
        // Held inline rather than read from examples/hello-zolt: the checked-in examples are written
        // in the final manifest language, which this legacy parser deliberately does not accept.
        ProjectConfig config = parser.parse("""
                [project]
                name = "hello-zolt"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]
                "com.google.guava:guava" = "33.4.0-jre"

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """);

        assertEquals("hello-zolt", config.project().name());
        assertEquals("0.1.0", config.project().version());
        assertEquals("com.example", config.project().group());
        assertEquals("21", config.project().java());
        assertEquals("com.example.Main", config.project().main().orElseThrow());
        assertEquals("https://repo.maven.apache.org/maven2", config.repositories().get("central"));
        assertTrue(config.platforms().isEmpty());
        assertTrue(config.apiDependencies().isEmpty());
        assertTrue(config.managedApiDependencies().isEmpty());
        assertTrue(config.workspaceApiDependencies().isEmpty());
        assertEquals("33.4.0-jre", config.dependencies().get("com.google.guava:guava"));
        assertTrue(config.testDependencies().isEmpty());
        assertTrue(config.managedDependencies().isEmpty());
        assertTrue(config.annotationProcessors().isEmpty());
        assertTrue(config.managedAnnotationProcessors().isEmpty());
        assertTrue(config.testAnnotationProcessors().isEmpty());
        assertTrue(config.managedTestAnnotationProcessors().isEmpty());
        assertEquals("src/main/java", config.build().source());
        assertEquals("target", config.build().outputRoot());
        assertEquals(List.of("src/test/java"), config.build().testSources());
        assertEquals("target/test-classes", config.build().testOutput());
        assertEquals("target/generated/sources/annotations", config.compilerSettings().generatedSources());
        assertEquals("target/generated/test-sources/annotations", config.compilerSettings().generatedTestSources());
        assertEquals("", config.compilerSettings().release());
        assertEquals("", config.compilerSettings().encoding());
        assertTrue(config.compilerSettings().args().isEmpty());
        assertTrue(config.compilerSettings().testArgs().isEmpty());
        assertEquals(PackageMode.THIN, config.packageSettings().mode());
    }

    @Test
    void appliesRepositoryAndBuildDefaultsWhenOptionalSectionsAreMissing() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "tiny"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        assertEquals(ProjectConfig.MAVEN_CENTRAL, config.repositories().get("central"));
        assertTrue(config.platforms().isEmpty());
        assertTrue(config.apiDependencies().isEmpty());
        assertTrue(config.managedApiDependencies().isEmpty());
        assertTrue(config.workspaceApiDependencies().isEmpty());
        assertTrue(config.dependencies().isEmpty());
        assertTrue(config.managedDependencies().isEmpty());
        assertTrue(config.annotationProcessors().isEmpty());
        assertTrue(config.managedAnnotationProcessors().isEmpty());
        assertTrue(config.testAnnotationProcessors().isEmpty());
        assertTrue(config.managedTestAnnotationProcessors().isEmpty());
        assertFalse(config.project().main().isPresent());
        assertEquals("src/main/java", config.build().source());
        assertEquals("target", config.build().outputRoot());
        assertEquals("target/classes", config.build().output());
        assertEquals("target/generated/sources/annotations", config.compilerSettings().generatedSources());
        assertEquals("target/generated/test-sources/annotations", config.compilerSettings().generatedTestSources());
        assertEquals("", config.compilerSettings().release());
        assertEquals("", config.compilerSettings().encoding());
        assertTrue(config.compilerSettings().args().isEmpty());
        assertTrue(config.compilerSettings().testArgs().isEmpty());
        assertEquals(PackageMode.THIN, config.packageSettings().mode());
        assertEquals("", config.nativeSettings().imageName());
        assertEquals("target/native", config.nativeSettings().output());
        assertTrue(config.nativeSettings().args().isEmpty());
    }

    @Test
    void acceptsZoltToolchainVersion() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "tiny"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [toolchain.zolt]
                version = "0.2.0"
                """);

        assertEquals("tiny", config.project().name());
    }

    @Test
    void parsesCompilerGeneratedSourceDirectories() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "processor-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [compiler]
                generatedSources = "build/generated/main"
                generatedTestSources = "build/generated/test"
                release = "17"
                encoding = "UTF-8"
                args = ["-Xlint:deprecation", "-parameters"]
                testArgs = ["-Xlint:unchecked"]
                """);

        assertEquals("build/generated/main", config.compilerSettings().generatedSources());
        assertEquals("build/generated/test", config.compilerSettings().generatedTestSources());
        assertEquals("17", config.compilerSettings().release());
        assertEquals("UTF-8", config.compilerSettings().encoding());
        assertEquals(List.of("-Xlint:deprecation", "-parameters"), config.compilerSettings().args());
        assertEquals(List.of("-Xlint:unchecked"), config.compilerSettings().testArgs());
    }

    @Test
    void parsesExplicitJavaTestSourceRoots() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.sources]
                java = ["src/test/java", "src/integration-test/java"]
                """);

        assertEquals(
                List.of("src/test/java", "src/integration-test/java"),
                config.build().testSources());
    }

    @Test
    void parsesExplicitMainSourceRoots() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [build]
                source = "src/main/java"
                sources = ["src/main/java", "src/generated/java"]
                """);

        assertEquals("src/main/java", config.build().source());
        assertEquals(
                List.of("src/main/java", "src/generated/java"),
                config.build().sourceRoots());
    }

    @Test
    void derivesBuildOutputsFromOutputRootWhenExplicitOutputsAreOmitted() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "migration-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [build]
                outputRoot = ".zolt/build"
                """);

        assertEquals(".zolt/build", config.build().outputRoot());
        assertEquals(".zolt/build/classes", config.build().output());
        assertEquals(".zolt/build/test-classes", config.build().testOutput());
        assertEquals(".zolt/build/generated/sources/annotations", config.compilerSettings().generatedSources());
        assertEquals(".zolt/build/generated/test-sources/annotations", config.compilerSettings().generatedTestSources());
        assertEquals(".zolt/build/native", config.nativeSettings().output());
    }

    @Test
    void explicitBuildOutputsOverrideOutputRootDerivedDefaults() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "migration-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [build]
                outputRoot = ".zolt/build"
                output = "out/main"
                testOutput = "out/test"
                """);

        assertEquals(".zolt/build", config.build().outputRoot());
        assertEquals("out/main", config.build().output());
        assertEquals("out/test", config.build().testOutput());
        assertEquals(".zolt/build/generated/sources/annotations", config.compilerSettings().generatedSources());
        assertEquals(".zolt/build/generated/test-sources/annotations", config.compilerSettings().generatedTestSources());
        assertEquals(".zolt/build/native", config.nativeSettings().output());
    }

    @Test
    void parsesExplicitGroovyTestSourceRoots() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.sources]
                java = ["src/test/java"]
                groovy = ["src/test/groovy", "src/integration-test/groovy"]
                """);

        assertEquals(List.of("src/test/java"), config.build().testSources());
        assertEquals(
                List.of("src/test/groovy", "src/integration-test/groovy"),
                config.build().groovyTestSources());
    }

    @Test
    void parsesIntegrationTestSettings() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [integrationTest]
                sources = ["src/it/java", "src/shared-it/java"]
                resources = ["src/it/resources"]
                output = "target/it-classes"
                """);

        assertEquals(List.of("src/it/java", "src/shared-it/java"), config.build().integrationTestSources());
        assertEquals(List.of("src/it/resources"), config.build().integrationTestResourceRoots());
        assertEquals("target/it-classes", config.build().integrationTestOutput());
    }

}
