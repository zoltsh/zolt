package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ResolveServiceSpringBootLoaderVariantTest
        extends ResolveServiceTestSupport {
    @Test
    void classifiedLoaderDoesNotSuppressDefaultLoaderForEitherArchiveMode() {
        addLoaderRepository();
        for (PackageMode mode : List.of(
                PackageMode.SPRING_BOOT,
                PackageMode.SPRING_BOOT_WAR)) {
            verifyClassifiedMode(mode);
        }
    }

    @Test
    void providedDefaultLoaderStillGetsAutoAddedRuntimeLoader() {
        addLoaderRepository();
        for (PackageMode mode : List.of(
                PackageMode.SPRING_BOOT,
                PackageMode.SPRING_BOOT_WAR)) {
            Path projectDir = tempDir.resolve(
                    mode.configValue() + "-provided");
            createDirectory(projectDir);
            ResolveResult result = resolveService.resolve(
                    projectDir,
                    providedConfig(mode),
                    tempDir.resolve(
                            mode.configValue() + "-provided-cache"));

            ZoltLockfile lockfile =
                    lockfileReader.read(result.lockfilePath());
            assertEquals(2, result.resolvedCount());
            assertTrue(lockfile.packages().stream()
                    .anyMatch(lockPackage ->
                            lockPackage.scope()
                                    == DependencyScope.PROVIDED
                                    && lockPackage.direct()
                                    && LockArtifactVariant.of(lockPackage)
                                            .isDefault()));
            assertTrue(lockfile.packages().stream()
                    .anyMatch(lockPackage ->
                            lockPackage.scope()
                                    == DependencyScope.RUNTIME
                                    && !lockPackage.direct()
                                    && LockArtifactVariant.of(lockPackage)
                                            .isDefault()));
        }
    }

    private void addLoaderRepository() {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-loader</artifactId>
                        <version>4.0.6</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-loader</artifactId>
                  <version>4.0.6</version>
                </project>
                """);
        addClassifierJar(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                "tests",
                Map.of("fixtures/LoaderTests.class", "tests"));
    }

    private void verifyClassifiedMode(PackageMode mode) {
        Path projectDir = tempDir.resolve(mode.configValue());
        createDirectory(projectDir);
        ResolveResult result = resolveService.resolve(
                projectDir,
                config(mode),
                tempDir.resolve(mode.configValue() + "-cache"));

        ZoltLockfile lockfile =
                lockfileReader.read(result.lockfilePath());
        assertEquals(2, result.resolvedCount());
        assertEquals(
                List.of("", "tests"),
                lockfile.packages().stream()
                        .filter(lockPackage -> lockPackage
                                .packageId()
                                .equals(new PackageId(
                                        "org.springframework.boot",
                                        "spring-boot-loader")))
                        .map(LockArtifactVariant::of)
                        .map(variant -> variant
                                .classifier()
                                .orElse(""))
                        .sorted()
                        .toList());
        assertTrue(lockfile.packages().stream()
                .filter(lockPackage ->
                        LockArtifactVariant.of(lockPackage).isDefault())
                .anyMatch(lockPackage ->
                        lockPackage.scope() == DependencyScope.RUNTIME
                                && !lockPackage.direct()));
        assertTrue(lockfile.packages().stream()
                .filter(lockPackage -> LockArtifactVariant.of(lockPackage)
                        .classifier()
                        .equals(Optional.of("tests")))
                .anyMatch(lockPackage ->
                        lockPackage.scope() == DependencyScope.RUNTIME
                                && lockPackage.direct()));
    }

    /** Legacy {@link PackageMode} to its final {@code [package].mode} symbol (design §17.2). */
    private static String manifestMode(PackageMode mode) {
        return switch (mode) {
            case THIN -> "jar";
            case UBER -> "uber-jar";
            default -> mode.configValue();
        };
    }

    private ProjectConfig config(PackageMode mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories.test]
                url = "%s"

                [platforms]
                "com.example:platform" = "1.0.0"

                [dependencies.runtime]
                "org.springframework.boot:spring-boot-loader" = { version = "4.0.6", classifier = "tests" }

                [package]
                mode = "%s"
                """.formatted(baseUri, manifestMode(mode)));
    }

    private ProjectConfig providedConfig(PackageMode mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories.test]
                url = "%s"

                [platforms]
                "com.example:platform" = "1.0.0"

                [dependencies.provided]
                "org.springframework.boot:spring-boot-loader" = "4.0.6"

                [package]
                mode = "%s"
                """.formatted(baseUri, manifestMode(mode)));
    }
}
