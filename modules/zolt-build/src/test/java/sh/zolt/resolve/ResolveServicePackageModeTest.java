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
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServicePackageModeTest extends ResolveServiceTestSupport {
    @Test
    void springBootPackageModeAddsLoaderFromProjectPlatform() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-loader</artifactId>
                        <version>4.0.6</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact("org.springframework.boot", "spring-boot-loader", "4.0.6", """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-loader</artifactId>
                  <version>4.0.6</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, springBootPlatformConfig(), cacheRoot);

        assertEquals(3, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.springframework.boot", "spring-boot-loader"))
                        && lockPackage.version().equals("4.0.6")
                        && lockPackage.scope() == DependencyScope.RUNTIME
                        && !lockPackage.direct()));
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "platform"))));
    }

    @Test
    void springBootWarPackageModeAddsLoaderFromProjectPlatform() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-loader</artifactId>
                        <version>4.0.6</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact("org.springframework.boot", "spring-boot-loader", "4.0.6", """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-loader</artifactId>
                  <version>4.0.6</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, springBootWarPlatformConfig(), cacheRoot);

        assertEquals(3, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.springframework.boot", "spring-boot-loader"))
                        && lockPackage.version().equals("4.0.6")
                        && lockPackage.scope() == DependencyScope.RUNTIME
                        && !lockPackage.direct()));
    }

    @Test
    void springBootNativeAddsAotToolingFromProjectPlatform() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot</artifactId>
                        <version>4.0.6</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact("org.springframework.boot", "spring-boot", "4.0.6", """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot</artifactId>
                  <version>4.0.6</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>spring-aot-helper</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "spring-aot-helper", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>spring-aot-helper</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, springBootNativePlatformConfig(), cacheRoot);

        assertEquals(4, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.springframework.boot", "spring-boot"))
                        && lockPackage.version().equals("4.0.6")
                        && lockPackage.scope() == DependencyScope.TOOL_SPRING_AOT
                        && !lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "spring-aot-helper"))
                        && lockPackage.scope() == DependencyScope.TOOL_SPRING_AOT
                        && !lockPackage.direct()));
        ClasspathSet classpaths = new ClasspathBuilder().build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        List<Path> normalClasspathEntries = new ArrayList<>();
        normalClasspathEntries.addAll(classpaths.compile().entries());
        normalClasspathEntries.addAll(classpaths.runtime().entries());
        normalClasspathEntries.addAll(classpaths.test().entries());
        normalClasspathEntries.addAll(classpaths.processor().entries());
        normalClasspathEntries.addAll(classpaths.testProcessor().entries());
        assertTrue(normalClasspathEntries.stream()
                .noneMatch(path -> path.toString().contains("spring-boot-4.0.6.jar")));
        assertTrue(normalClasspathEntries.stream()
                .noneMatch(path -> path.toString().contains("spring-aot-helper-1.0.0.jar")));
    }

    @Test
    void springBootPackageModeKeepsExplicitLoaderDependencyDirect() {
        addArtifact("org.springframework.boot", "spring-boot-loader", "4.0.6", """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-loader</artifactId>
                  <version>4.0.6</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithDependencies(Map.of("org.springframework.boot:spring-boot-loader", "4.0.6"))
                        .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                cacheRoot);

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.springframework.boot", "spring-boot-loader"))
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.direct()));
    }

    @Test
    void springBootPackageModeFailsClearlyWithoutManagedLoaderVersion() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDir,
                        config().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                        cacheRoot));

        assertTrue(exception.getMessage().contains("Spring Boot package mode requires package tool artifact"));
        assertTrue(exception.getMessage().contains("Add the Spring Boot platform to [platforms]"));
        assertTrue(exception.getMessage().contains("run `zolt resolve`"));
    }

    @Test
    void springBootWarPackageModeFailsClearlyWithoutManagedLoaderVersion() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDir,
                        config().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT_WAR)),
                        cacheRoot));

        assertTrue(exception.getMessage().contains("Spring Boot package mode requires package tool artifact"));
        assertTrue(exception.getMessage().contains("Add the Spring Boot platform to [platforms]"));
        assertTrue(exception.getMessage().contains("run `zolt resolve`"));
    }

}
