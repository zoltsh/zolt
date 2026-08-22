package sh.zolt.build.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClasspathBuilderTest {
    private final ClasspathBuilder builder = new ClasspathBuilder();

    @Test
    void compileDependenciesAreIncludedOnCompileAndRuntimeClasspaths() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "compile-lib", "1.0.0", DependencyScope.COMPILE)));

        Path jar = jar("compile-lib", "1.0.0");
        assertEquals(List.of(jar), classpaths.compile().entries());
        assertEquals(List.of(jar), classpaths.runtime().entries());
    }

    @Test
    void runtimeDependenciesAreIncludedOnRuntimeClasspathOnly() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "runtime-lib", "1.0.0", DependencyScope.RUNTIME)));

        Path jar = jar("runtime-lib", "1.0.0");
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(jar), classpaths.runtime().entries());
    }

    @Test
    void devDependenciesAreIncludedOnRuntimeClasspathButNotPackaged() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "dev-lib", "1.0.0", DependencyScope.DEV)));

        Path jar = jar("dev-lib", "1.0.0");
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(jar), classpaths.runtime().entries());
        assertFalse(DependencyScope.DEV.packagedByDefault());
    }

    @Test
    void testDependenciesAreIncludedOnlyOnTestClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "test-lib", "1.0.0", DependencyScope.TEST)));

        Path jar = jar("test-lib", "1.0.0");
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(jar), classpaths.test().entries());
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
    }

    @Test
    void providedDependencyIsOnTestCompileClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "provided-lib", "1.0.0", DependencyScope.PROVIDED)));

        assertEquals(List.of(jar("provided-lib", "1.0.0")), classpaths.testCompile().entries());
    }

    @Test
    void providedDependencyIsOnTestRuntimeClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "provided-lib", "1.0.0", DependencyScope.PROVIDED)));

        assertEquals(List.of(jar("provided-lib", "1.0.0")), classpaths.test().entries());
    }

    @Test
    void devDependencyIsAbsentFromTestCompileClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "dev-lib", "1.0.0", DependencyScope.DEV)));

        assertEquals(List.of(), classpaths.testCompile().entries());
    }

    @Test
    void devDependencyIsAbsentFromTestRuntimeClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "dev-lib", "1.0.0", DependencyScope.DEV)));

        assertEquals(List.of(), classpaths.test().entries());
    }

    @Test
    void providedDependenciesStayOffMainRuntimeAndProcessorClasspaths() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope("com.example", "provided-lib", "1.0.0", DependencyScope.PROVIDED)));

        Path jar = jar("provided-lib", "1.0.0");
        assertEquals(List.of(jar), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
    }

    @Test
    void testCompileClasspathMatchesTestRuntimeForNonProvidedScopes() {
        ClasspathSet classpaths = builder.build(List.of(
                packageWithScope("com.example", "compile-lib", "1.0.0", DependencyScope.COMPILE),
                packageWithScope("com.example", "runtime-lib", "1.0.0", DependencyScope.RUNTIME),
                packageWithScope("com.example", "test-lib", "1.0.0", DependencyScope.TEST)));

        assertEquals(classpaths.test().entries(), classpaths.testCompile().entries());
    }

    @Test
    void nonProvidedCompileOnlyScopeStaysOffTestCompileClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope(
                "com.example",
                "processor-only-lib",
                "1.0.0",
                DependencyScope.PROCESSOR)));

        assertEquals(List.of(), classpaths.testCompile().entries());
        assertEquals(List.of(), classpaths.test().entries());
    }

    @Test
    void processorDependenciesAreIncludedOnlyOnProcessorClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope(
                "com.example",
                "processor-lib",
                "1.0.0",
                DependencyScope.PROCESSOR)));

        Path jar = jar("processor-lib", "1.0.0");
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.test().entries());
        assertEquals(List.of(jar), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
    }

    @Test
    void processorClasspathIncludesTransitiveProcessorPackagesOnly() {
        ClasspathSet classpaths = builder.build(List.of(
                packageWithScope("com.framework", "processor-api", "1.0.0", DependencyScope.PROCESSOR, false),
                packageWithScope("com.framework", "processor-core", "1.0.0", DependencyScope.PROCESSOR, true),
                packageWithScope("com.framework", "runtime-support", "1.0.0", DependencyScope.RUNTIME, false)));

        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(jar("runtime-support", "1.0.0")), classpaths.runtime().entries());
        assertEquals(List.of(jar("runtime-support", "1.0.0")), classpaths.test().entries());
        assertEquals(List.of(
                jar("processor-api", "1.0.0"),
                jar("processor-core", "1.0.0")), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
    }

    @Test
    void testProcessorDependenciesAreIncludedOnlyOnTestProcessorClasspath() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope(
                "com.example",
                "test-processor-lib",
                "1.0.0",
                DependencyScope.TEST_PROCESSOR)));

        Path jar = jar("test-processor-lib", "1.0.0");
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.test().entries());
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(jar), classpaths.testProcessor().entries());
    }

    @Test
    void quarkusDeploymentDependenciesAreExcludedFromNormalClasspaths() {
        ClasspathSet classpaths = builder.build(List.of(packageWithScope(
                "io.quarkus",
                "quarkus-rest-deployment",
                "3.33.0",
                DependencyScope.QUARKUS_DEPLOYMENT)));

        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.test().entries());
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
        assertFalse(DependencyScope.QUARKUS_DEPLOYMENT.packagedByDefault());
    }

    @Test
    void testClasspathIncludesRuntimeNeededForTests() {
        ClasspathSet classpaths = builder.build(List.of(
                packageWithScope("com.example", "compile-lib", "1.0.0", DependencyScope.COMPILE),
                packageWithScope("com.example", "runtime-lib", "1.0.0", DependencyScope.RUNTIME),
                packageWithScope("com.example", "test-lib", "1.0.0", DependencyScope.TEST)));

        assertEquals(List.of(
                jar("compile-lib", "1.0.0"),
                jar("runtime-lib", "1.0.0"),
                jar("test-lib", "1.0.0")), classpaths.test().entries());
    }

    @Test
    void classpathEntriesAreDeterministicAndDeduplicated() {
        ResolvedClasspathPackage alpha = packageWithScope("com.example", "alpha", "1.0.0", DependencyScope.COMPILE);
        ResolvedClasspathPackage zeta = packageWithScope("com.example", "zeta", "1.0.0", DependencyScope.COMPILE);

        ClasspathSet classpaths = builder.build(List.of(zeta, alpha, alpha));

        assertEquals(List.of(jar("alpha", "1.0.0"), jar("zeta", "1.0.0")), classpaths.compile().entries());
    }

    private static ResolvedClasspathPackage packageWithScope(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope) {
        return packageWithScope(groupId, artifactId, version, scope, false);
    }

    private static ResolvedClasspathPackage packageWithScope(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean direct) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId(groupId, artifactId),
                        version,
                        direct,
                        Path.of("cache", artifactId, artifactId + "-" + version + ".pom"),
                        jar(artifactId, version)),
                scope);
    }

    private static Path jar(String artifactId, String version) {
        return Path.of("cache", artifactId, artifactId + "-" + version + ".jar");
    }
}
