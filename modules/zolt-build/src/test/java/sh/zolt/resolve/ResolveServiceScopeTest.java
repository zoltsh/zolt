package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ResolveServiceScopeTest extends ResolveServiceTestSupport {
    @Test
    void directRuntimeAndProvidedDependenciesUseDistinctScopes() {
        addArtifact("com.example", "runtime-tool", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>runtime-tool</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        addArtifact("jakarta.servlet", "jakarta.servlet-api", "6.1.0", """
                <project>
                  <groupId>jakarta.servlet</groupId>
                  <artifactId>jakarta.servlet-api</artifactId>
                  <version>6.1.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, runtimeProvidedConfig(), cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "runtime-tool"))
                        && lockPackage.scope() == DependencyScope.RUNTIME
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("jakarta.servlet", "jakarta.servlet-api"))
                        && lockPackage.scope() == DependencyScope.PROVIDED
                        && lockPackage.direct()));

        ClasspathSet classpaths = new ClasspathBuilder().build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        assertEquals(List.of(
                cachedJar(lockfile, cacheRoot, "jakarta.servlet", "jakarta.servlet-api", DependencyScope.PROVIDED)),
                classpaths.compile().entries());
        assertEquals(List.of(
                cachedJar(lockfile, cacheRoot, "com.example", "runtime-tool", DependencyScope.RUNTIME)),
                classpaths.runtime().entries());
    }

    @Test
    void directDevDependenciesAndTransitivesUseDevScope() {
        addArtifact("com.example", "devtools", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>devtools</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>dev-helper</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "dev-helper", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>dev-helper</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, devConfig(), cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "devtools"))
                        && lockPackage.scope() == DependencyScope.DEV
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "dev-helper"))
                        && lockPackage.scope() == DependencyScope.DEV
                        && !lockPackage.direct()));

        ClasspathSet classpaths = new ClasspathBuilder().build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(
                cachedJar(lockfile, cacheRoot, "com.example", "dev-helper", DependencyScope.DEV),
                cachedJar(lockfile, cacheRoot, "com.example", "devtools", DependencyScope.DEV)),
                classpaths.runtime().entries());
    }

    @Test
    void directClassifiedDependencyResolvesClassifierQualifiedJarDeterministically() {
        addPom("com.example", "native-epoll", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>native-epoll</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        addClassifierJar(
                "com.example",
                "native-epoll",
                "1.0.0",
                "linux-x86_64",
                Map.of("META-INF/native/libnative.so", "binary"));
        ProjectConfig config = classifiedDependencyConfig();
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage nativePackage = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "native-epoll")))
                .findFirst()
                .orElseThrow();
        assertTrue(nativePackage.jar().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(nativePackage.jar().orElseThrow().endsWith("/native-epoll-1.0.0-linux-x86_64.jar"));
        assertTrue(nativePackage.jarSha256().isPresent());
        assertTrue(nativePackage.direct());
        assertEquals(DependencyScope.COMPILE, nativePackage.scope());

        ClasspathSet classpaths = new ClasspathBuilder().build(
                LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        assertEquals(List.of(
                cacheRoot.resolve(nativePackage.jar().orElseThrow())),
                classpaths.compile().entries());

        ZoltLockfile reread = lockfileReader.read(result.lockfilePath());
        assertEquals(lockfile.packages(), reread.packages());
    }

    @Test
    void classifiedTestJarCoexistsWithUnclassifiedCompileJar() {
        addArtifact("org.junit.platform", "junit-platform-console", "1.11.4", """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-console</artifactId>
                  <version>1.11.4</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-launcher</artifactId>
                      <version>1.11.4</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("org.junit.platform", "junit-platform-launcher", "1.11.4", """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-launcher</artifactId>
                  <version>1.11.4</version>
                </project>
                """);
        addClassifierJar(
                "com.example",
                "lib",
                "1.0.0",
                "tests",
                Map.of("com/example/lib/LibTestSupport.class", "test-support"));
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of("com.example:lib", "1.0.0"),
                Map.of("com.example:lib", "1.0.0"),
                BuildSettings.defaults())
                .withDependencyMetadata(Map.of(
                        DependencyMetadata.key("test.dependencies", "com.example:lib"),
                        new DependencyMetadata(
                                "test.dependencies",
                                "com.example:lib",
                                "1.0.0",
                                null,
                                false,
                                null,
                                false,
                                false,
                                List.of(),
                                "tests",
                                null)));
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage compileJar = lockPackage(lockfile, DependencyScope.COMPILE);
        LockPackage testJar = lockPackage(lockfile, DependencyScope.TEST);
        assertTrue(compileJar.jar().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(compileJar.jar().orElseThrow().endsWith("/lib-1.0.0.jar"));
        assertTrue(testJar.jar().orElseThrow().startsWith("blobs/v2/sha256/"));
        assertTrue(testJar.jar().orElseThrow().endsWith("/lib-1.0.0-tests.jar"));
    }

    private static LockPackage lockPackage(ZoltLockfile lockfile, DependencyScope scope) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "lib")))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .findFirst()
                .orElseThrow();
    }

    private static Path cachedJar(
            ZoltLockfile lockfile,
            Path cacheRoot,
            String groupId,
            String artifactId,
            DependencyScope scope) {
        return cacheRoot.resolve(lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(groupId, artifactId)))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .findFirst()
                .orElseThrow()
                .jar()
                .orElseThrow());
    }

    private ProjectConfig classifiedDependencyConfig() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of("com.example:native-epoll", "1.0.0"),
                Map.of(),
                BuildSettings.defaults())
                .withDependencyMetadata(Map.of(
                        DependencyMetadata.key("dependencies", "com.example:native-epoll"),
                        new DependencyMetadata(
                                "dependencies",
                                "com.example:native-epoll",
                                "1.0.0",
                                null,
                                false,
                                null,
                                false,
                                false,
                                List.of(),
                                "linux-x86_64",
                                null)));
    }
}
