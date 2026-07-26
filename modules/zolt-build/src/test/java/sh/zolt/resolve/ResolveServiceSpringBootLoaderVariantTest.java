package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ResolveServiceSpringBootLoaderVariantTest
        extends ResolveServiceTestSupport {
    @Test
    void classifiedLoaderDoesNotSuppressDefaultLoaderForEitherArchiveMode() {
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

        for (PackageMode mode : List.of(
                PackageMode.SPRING_BOOT,
                PackageMode.SPRING_BOOT_WAR)) {
            verifyMode(mode);
        }
    }

    private void verifyMode(PackageMode mode) {
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

    private ProjectConfig config(PackageMode mode) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                test = "%s"

                [platforms]
                "com.example:platform" = "1.0.0"

                [runtime.dependencies]
                "org.springframework.boot:spring-boot-loader" = { version = "4.0.6", classifier = "tests" }

                [package]
                mode = "%s"
                """.formatted(baseUri, mode.configValue()));
    }
}
