package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResolveServiceGeneratedSourceTest extends ResolveServiceTestSupport {
    @Test
    void annotationProcessorsResolveToProcessorScopesOnly() {
        addArtifact("com.example", "processor", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>processor</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>processor-helper</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "processor-helper", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>processor-helper</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, processorConfig(), cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "processor"))
                        && lockPackage.scope() == DependencyScope.PROCESSOR
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "processor-helper"))
                        && lockPackage.scope() == DependencyScope.PROCESSOR
                        && !lockPackage.direct()));

        ClasspathSet classpaths = new ClasspathBuilder().build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.test().entries());
        assertEquals(List.of(
                cachedJar(lockfile, cacheRoot, "processor-helper"),
                cachedJar(lockfile, cacheRoot, "processor")),
                classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
    }

    private static Path cachedJar(ZoltLockfile lockfile, Path cacheRoot, String artifactId) {
        return cacheRoot.resolve(lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", artifactId)))
                .findFirst()
                .orElseThrow()
                .jar()
                .orElseThrow());
    }

    @Test
    void openApiToolResolvesToToolScopeOnly() {
        addArtifact("org.openapitools", "openapi-generator-cli", "7.11.0", """
                <project>
                  <groupId>org.openapitools</groupId>
                  <artifactId>openapi-generator-cli</artifactId>
                  <version>7.11.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>generator-helper</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "generator-helper", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>generator-helper</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, openApiConfig(), cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.openapitools", "openapi-generator-cli"))
                        && lockPackage.scope() == DependencyScope.TOOL_OPENAPI
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "generator-helper"))
                        && lockPackage.scope() == DependencyScope.TOOL_OPENAPI
                        && !lockPackage.direct()));

        ClasspathSet classpaths = new ClasspathBuilder().build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.test().entries());
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
        assertEquals(List.of(), classpaths.quarkusDeployment().entries());
    }

    @Test
    void protobufToolsResolveToToolScopeOnly() {
        addArtifact("com.google.protobuf", "protoc", "4.28.3", """
                <project>
                  <groupId>com.google.protobuf</groupId>
                  <artifactId>protoc</artifactId>
                  <version>4.28.3</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>protobuf-tool-helper</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("io.grpc", "protoc-gen-grpc-java", "1.68.1", """
                <project>
                  <groupId>io.grpc</groupId>
                  <artifactId>protoc-gen-grpc-java</artifactId>
                  <version>1.68.1</version>
                </project>
                """);
        addArtifact("com.example", "protobuf-tool-helper", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>protobuf-tool-helper</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, ResolveGeneratedSourceTestConfigs.protobufConfig(baseUri), cacheRoot);

        assertEquals(3, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.google.protobuf", "protoc"))
                        && lockPackage.scope() == DependencyScope.TOOL_PROTOBUF
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.grpc", "protoc-gen-grpc-java"))
                        && lockPackage.scope() == DependencyScope.TOOL_PROTOBUF
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "protobuf-tool-helper"))
                        && lockPackage.scope() == DependencyScope.TOOL_PROTOBUF
                        && !lockPackage.direct()));

        ClasspathSet classpaths = new ClasspathBuilder().build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        assertEquals(List.of(), classpaths.compile().entries());
        assertEquals(List.of(), classpaths.runtime().entries());
        assertEquals(List.of(), classpaths.test().entries());
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
        assertEquals(List.of(), classpaths.quarkusDeployment().entries());
    }

    @Test
    void projectPlatformProvidesManagedVersionForAnnotationProcessor() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>processor</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact("com.example", "processor", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>processor</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, processorPlatformConfig(), cacheRoot);

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "processor"))
                        && lockPackage.version().equals("1.0.0")
                        && lockPackage.scope() == DependencyScope.PROCESSOR
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "platform"))));
    }

    @Test
    void unmanagedAnnotationProcessorFailsClearly() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>other</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, processorPlatformConfig(), cacheRoot));

        assertTrue(exception.getMessage().contains("Dependency com.example:processor in [annotationProcessors]"));
        assertTrue(exception.getMessage().contains("uses a platform-managed version"));
    }
}
