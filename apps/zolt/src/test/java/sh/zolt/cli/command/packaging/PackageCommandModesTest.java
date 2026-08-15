package sh.zolt.cli.command.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.build.PackageException;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;

final class PackageCommandModesTest {
    private final ZoltTomlParser parser = new ZoltTomlParser();

    @Test
    void packagingLocalOverrideDoesNotChangeResolutionInputs() {
        ProjectConfig overridden = PackageCommandModes.withPackageModeOverride(
                config(PackageMode.THIN),
                Optional.of(PackageMode.UBER));

        assertEquals(PackageMode.UBER, overridden.packageSettings().mode());
    }

    @Test
    void springBootJarAndWarOverridesShareResolutionTooling() {
        ProjectConfig overridden = PackageCommandModes.withPackageModeOverride(
                config(PackageMode.SPRING_BOOT),
                Optional.of(PackageMode.SPRING_BOOT_WAR));

        assertEquals(PackageMode.SPRING_BOOT_WAR, overridden.packageSettings().mode());
    }

    @Test
    void overrideThatChangesResolutionToolingFailsClosed() {
        PackageException exception = assertThrows(
                PackageException.class,
                () -> PackageCommandModes.withPackageModeOverride(
                        config(PackageMode.THIN),
                        Optional.of(PackageMode.SPRING_BOOT)));

        assertTrue(exception.getMessage().contains("changes dependency-resolution tooling"));
        assertTrue(exception.getMessage().contains("[package].mode = \"spring-boot\""));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void packageHelpExplainsThePersistentSpringBootTransition() {
        String help = execute("package", "--help").stdout();

        assertTrue(help.contains("Temporary package mode override"));
        assertTrue(help.contains("Persist [package].mode"));
        assertTrue(help.contains("zolt resolve"));
    }

    private ProjectConfig config(PackageMode mode) {
        return parser.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [package]
                mode = "%s"
                """.formatted(mode.configValue()));
    }
}
