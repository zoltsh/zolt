package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.repository.PomInterpolationException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClassifierArtifactResolutionTest extends ResolveServiceTestSupport {
    @Test
    void keepsTwoTransitiveClassifiersOfOneGaInTheSameScope() {
        addArtifact("com.example", "variant-app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>variant-app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>fixture</artifactId>
                      <version>1.0.0</version>
                      <classifier>linux-x86_64</classifier>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>fixture</artifactId>
                      <version>1.0.0</version>
                      <classifier>tests</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "fixture", "1.0.0", simplePom("com.example", "fixture", "1.0.0"));
        addClassifierJar("com.example", "fixture", "1.0.0", "linux-x86_64", Map.of());
        addClassifierJar("com.example", "fixture", "1.0.0", "tests", Map.of());
        Path projectDir = tempDir.resolve("two-transitive-variants");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithDependencies(Map.of("com.example:variant-app", "1.0.0")),
                tempDir.resolve("two-transitive-variants-cache"));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertEquals(3, result.resolvedCount());
        assertEquals(
                List.of("linux-x86_64", "tests"),
                lockfile.packages().stream()
                        .filter(pkg -> pkg.packageId().equals(new PackageId("com.example", "fixture")))
                        .map(LockArtifactVariant::of)
                        .map(variant -> variant.classifier().orElseThrow())
                        .sorted()
                        .toList());
    }

    @Test
    void mainAndTestVariantsMediateIndependentlyAtDifferentVersions() {
        addJUnitConsoleArtifact("1.11.4");
        addArtifact("com.example", "fixture", "1.0.0", simplePom("com.example", "fixture", "1.0.0"));
        addArtifact("com.example", "fixture", "2.0.0", simplePom("com.example", "fixture", "2.0.0"));
        addClassifierJar("com.example", "fixture", "1.0.0", "linux-x86_64", Map.of());
        addClassifierJar("com.example", "fixture", "2.0.0", "tests", Map.of());
        Path projectDir = tempDir.resolve("main-test-variants");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                config("""
                        [dependencies]
                        "com.example:fixture" = { version = "1.0.0", classifier = "linux-x86_64" }

                        [test.dependencies]
                        "com.example:fixture" = { version = "2.0.0", classifier = "tests" }

                        [dependencyPolicy]
                        failOnVersionConflict = true
                        """),
                tempDir.resolve("main-test-variants-cache"));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertEquals(0, result.conflictCount());
        assertVersionPathInvariant(lockfile, "linux-x86_64", "1.0.0");
        assertVersionPathInvariant(lockfile, "tests", "2.0.0");
    }

    @Test
    void dependencyAndAnnotationProcessorVariantsRemainSeparate() {
        addArtifact("com.example", "fixture", "1.0.0", simplePom("com.example", "fixture", "1.0.0"));
        addArtifact("com.example", "fixture", "2.0.0", simplePom("com.example", "fixture", "2.0.0"));
        addClassifierJar("com.example", "fixture", "1.0.0", "runtime", Map.of());
        addClassifierJar("com.example", "fixture", "2.0.0", "processor", Map.of());
        Path projectDir = tempDir.resolve("dependency-processor-variants");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                config("""
                        [dependencies]
                        "com.example:fixture" = { version = "1.0.0", classifier = "runtime" }

                        [annotationProcessors]
                        "com.example:fixture" = { version = "2.0.0", classifier = "processor" }
                        """),
                tempDir.resolve("dependency-processor-variants-cache"));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertEquals(
                List.of(DependencyScope.COMPILE, DependencyScope.PROCESSOR),
                lockfile.packages().stream()
                        .filter(pkg -> pkg.packageId().equals(new PackageId("com.example", "fixture")))
                        .map(LockPackage::scope)
                        .sorted()
                        .toList());
        assertVersionPathInvariant(lockfile, "runtime", "1.0.0");
        assertVersionPathInvariant(lockfile, "processor", "2.0.0");
    }

    @Test
    void resolvesFixedRuntimeClassifierArtifactFromPomDependency() {
        addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>native-lib</artifactId>
                      <version>1.0.0</version>
                      <scope>runtime</scope>
                      <classifier>linux-x86_64</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "native-lib", "1.0.0", simplePom("com.example", "native-lib", "1.0.0"));
        addClassifierJar("com.example", "native-lib", "1.0.0", "linux-x86_64", Map.of(
                "com/example/NativeMarker.class", "classifier jar"));
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithDependencies(Map.of("com.example:app", "1.0.0")),
                cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage nativeLib = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "native-lib")))
                .findFirst()
                .orElseThrow();
        assertEquals(DependencyScope.RUNTIME, nativeLib.scope());
        assertTrue(nativeLib.jar().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(nativeLib.jar().orElseThrow().endsWith("/native-lib-1.0.0-linux-x86_64.jar"));
        assertEquals(1, requestCount("/maven2/com/example/native-lib/1.0.0/native-lib-1.0.0-linux-x86_64.jar"));
        assertEquals(0, requestCount("/maven2/com/example/native-lib/1.0.0/native-lib-1.0.0.jar"));
    }

    @Test
    void transitiveTestAndProvidedDynamicClassifierMetadataDoesNotBlockRuntimeResolve() {
        addArtifact("com.example", "starter", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>starter</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>api-helper</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>io.netty</groupId>
                      <artifactId>netty-transport-native-epoll</artifactId>
                      <version>4.2.12.Final</version>
                      <classifier>${os.detected.classifier}</classifier>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>software.amazon.cryptools</groupId>
                      <artifactId>AmazonCorrettoCryptoProvider</artifactId>
                      <version>2.5.0</version>
                      <classifier>${corretto.classifier}</classifier>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "api-helper", "1.0.0", simplePom("com.example", "api-helper", "1.0.0"));
        Path projectDir = tempDir.resolve("classifier-non-runtime-project");
        Path cacheRoot = tempDir.resolve("classifier-non-runtime-cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithDependencies(Map.of("com.example:starter", "1.0.0")),
                cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertPackage(lockfile, "com.example", "starter", "1.0.0", DependencyScope.COMPILE, true);
        assertPackage(lockfile, "com.example", "api-helper", "1.0.0", DependencyScope.COMPILE, false);
        assertAbsent(lockfile, "io.netty", "netty-transport-native-epoll");
        assertAbsent(lockfile, "software.amazon.cryptools", "AmazonCorrettoCryptoProvider");
    }

    @Test
    void offlineResolveReportsMissingClassifierArtifactWithCacheSeedingStep() throws IOException {
        addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>native-lib</artifactId>
                      <version>1.0.0</version>
                      <scope>runtime</scope>
                      <classifier>linux-x86_64</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "native-lib", "1.0.0", simplePom("com.example", "native-lib", "1.0.0"));
        addClassifierJar("com.example", "native-lib", "1.0.0", "linux-x86_64", Map.of());
        Path projectDir = tempDir.resolve("offline-project");
        Path cacheRoot = tempDir.resolve("offline-cache");
        createDirectory(projectDir);
        ResolveResult resolved = resolveService.resolve(
                projectDir,
                configWithDependencies(Map.of("com.example:app", "1.0.0")),
                cacheRoot);
        ZoltLockfile resolvedLock = lockfileReader.read(resolved.lockfilePath());
        LockPackage nativeLib = resolvedLock.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "native-lib")))
                .findFirst()
                .orElseThrow();
        Files.delete(cacheRoot.resolve(nativeLib.jar().orElseThrow()));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDir,
                        configWithDependencies(Map.of("com.example:app", "1.0.0")),
                        cacheRoot,
                        false,
                        true));

        assertTrue(exception.getMessage().contains("native-lib-1.0.0-linux-x86_64.jar"));
        assertTrue(exception.getMessage().contains("Run the command without --offline"));
    }

    @Test
    void rejectsDynamicClassifierSelectionWithActionableDiagnostic() {
        addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>native-lib</artifactId>
                      <version>1.0.0</version>
                      <classifier>${os.detected.classifier}</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        createDirectory(projectDir);

        PomInterpolationException exception = assertThrows(
                PomInterpolationException.class,
                () -> resolveService.resolve(
                        projectDir,
                        configWithDependencies(Map.of("com.example:app", "1.0.0")),
                        tempDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("Dynamic classifier selection"));
        assertTrue(exception.getMessage().contains("${os.detected.classifier}"));
        assertTrue(exception.getMessage().contains("fixed OS/architecture classifier"));
    }

    @Test
    void rejectsRuntimeDynamicClassifierSelectionWithActionableDiagnostic() {
        addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>native-lib</artifactId>
                      <version>1.0.0</version>
                      <scope>runtime</scope>
                      <classifier>${os.detected.classifier}</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path projectDir = tempDir.resolve("runtime-classifier-project");
        createDirectory(projectDir);

        PomInterpolationException exception = assertThrows(
                PomInterpolationException.class,
                () -> resolveService.resolve(
                        projectDir,
                        configWithDependencies(Map.of("com.example:app", "1.0.0")),
                        tempDir.resolve("runtime-classifier-cache")));

        assertTrue(exception.getMessage().contains("Dynamic classifier selection"));
        assertTrue(exception.getMessage().contains("${os.detected.classifier}"));
        assertTrue(exception.getMessage().contains("fixed OS/architecture classifier"));
    }

    private static void assertPackage(
            ZoltLockfile lockfile,
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean direct) {
        LockPackage lockPackage = lockfile.packages().stream()
                .filter(candidate -> candidate.packageId().equals(new PackageId(groupId, artifactId)))
                .findFirst()
                .orElseThrow();
        assertEquals(version, lockPackage.version());
        assertEquals(scope, lockPackage.scope());
        assertEquals(direct, lockPackage.direct());
    }

    private static void assertAbsent(ZoltLockfile lockfile, String groupId, String artifactId) {
        assertFalse(lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().equals(new PackageId(groupId, artifactId))));
    }

    private ProjectConfig config(String sections) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "variant-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                test = "%s"

                %s
                """.formatted(baseUri, sections));
    }

    private static void assertVersionPathInvariant(
            ZoltLockfile lockfile, String classifier, String version) {
        LockPackage lockPackage = lockfile.packages().stream()
                .filter(pkg -> pkg.packageId().equals(new PackageId("com.example", "fixture")))
                .filter(pkg -> LockArtifactVariant.of(pkg).classifier().orElse("").equals(classifier))
                .findFirst()
                .orElseThrow();
        assertEquals(version, lockPackage.version());
        assertTrue(lockPackage.jar().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(lockPackage.jar().orElseThrow().endsWith(
                "/fixture-%s-%s.jar".formatted(version, classifier)));
        assertTrue(lockPackage.pom().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(lockPackage.pom().orElseThrow().endsWith(
                "/fixture-%s.pom".formatted(version)));
    }
}
