package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class ResolveServiceCompilerToolRelocationTest extends ResolveServiceTestSupport {
    @Test
    void rejectsGroovyCompilerRootGroupAndArtifactRelocationBeforeWritingTheLockfile() {
        addPom("org.apache.groovy", "groovy", "4.0.22", """
                <project>
                  <groupId>org.apache.groovy</groupId>
                  <artifactId>groovy</artifactId>
                  <version>4.0.22</version>
                  <distributionManagement>
                    <relocation>
                      <groupId>com.example</groupId>
                      <artifactId>relocated-groovy</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        addArtifact(
                "com.example",
                "relocated-groovy",
                "4.0.22",
                simplePom("com.example", "relocated-groovy", "4.0.22"));
        Path projectDirectory = tempDir.resolve("groovy-relocation");
        Path cacheRoot = tempDir.resolve("groovy-relocation-cache");
        createDirectory(projectDirectory);

        ResolveException exception = assertThrows(ResolveException.class, () -> resolveService.resolve(
                projectDirectory,
                config("groovy", "4.0.22"),
                cacheRoot,
                false,
                ResolveOptions.defaults().withRetryCommand("zolt resolve --locked")));

        assertEquals(
                "The POM for configured Groovy compiler root `org.apache.groovy:groovy:4.0.22` in "
                        + "[toolchain.groovy] relocates to `com.example:relocated-groovy:4.0.22`. "
                        + "Zolt requires compiler roots to preserve their exact configured Maven coordinate.",
                exception.getMessage());
        assertEquals(
                "Select a non-relocated Groovy compiler version in [toolchain.groovy], then run "
                        + "`zolt resolve --locked` again.",
                exception.actionableError().remediation());
        assertFalse(Files.exists(projectDirectory.resolve("zolt.lock")));
    }

    @Test
    void rejectsKotlinCompilerRootVersionRelocationBeforeWritingTheLockfile() {
        addPom("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.0", """
                <project>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-compiler-embeddable</artifactId>
                  <version>2.2.0</version>
                  <distributionManagement>
                    <relocation>
                      <version>2.2.10</version>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        addArtifact(
                "org.jetbrains.kotlin",
                "kotlin-compiler-embeddable",
                "2.2.10",
                simplePom(
                        "org.jetbrains.kotlin",
                        "kotlin-compiler-embeddable",
                        "2.2.10"));
        Path projectDirectory = tempDir.resolve("kotlin-relocation");
        Path cacheRoot = tempDir.resolve("kotlin-relocation-cache");
        createDirectory(projectDirectory);

        ResolveException exception = assertThrows(ResolveException.class, () -> resolveService.resolve(
                projectDirectory,
                config("kotlin", "2.2.0"),
                cacheRoot,
                false,
                ResolveOptions.defaults().withRetryCommand("zolt resolve --locked")));

        assertEquals(
                "The POM for configured Kotlin compiler root "
                        + "`org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0` in "
                        + "[toolchain.kotlin] relocates to "
                        + "`org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.10`. "
                        + "Zolt requires compiler roots to preserve their exact configured Maven coordinate.",
                exception.getMessage());
        assertEquals(
                "Select a non-relocated Kotlin compiler version in [toolchain.kotlin], then run "
                        + "`zolt resolve --locked` again.",
                exception.actionableError().remediation());
        assertFalse(Files.exists(projectDirectory.resolve("zolt.lock")));
    }

    private ProjectConfig config(String compiler, String version) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "compiler-relocation"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [toolchain.%s]
                version = "%s"
                """.formatted(baseUri, compiler, version));
    }
}
