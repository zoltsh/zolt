package sh.zolt.cli.surface;

import static sh.zolt.cli.CliTestSupport.newCommandLine;
import static sh.zolt.cli.surface.CliHelpSurfaceFixtures.commandPaths;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class CliFocusedHelpSurfaceCoverageTest {
    private static final Set<String> FOCUSED_HELP_SURFACE_PATHS = Set.of(
            "zolt",
            "zolt add",
            "zolt aliases",
            "zolt bom",
            "zolt bom imports",
            "zolt bom imports remove",
            "zolt bom imports set",
            "zolt bom versions",
            "zolt bom versions remove",
            "zolt bom versions set",
            "zolt build",
            "zolt cache",
            "zolt cache prune",
            "zolt cache status",
            "zolt check",
            "zolt classpath",
            "zolt clean",
            "zolt config",
            "zolt config show",
            "zolt conflicts",
            "zolt coverage",
            "zolt doctor",
            "zolt exec",
            "zolt explain",
            "zolt explain verify",
            "zolt help",
            "zolt ide",
            "zolt ide model",
            "zolt init",
            "zolt integration-test",
            "zolt licenses",
            "zolt native",
            "zolt native-smoke",
            "zolt outdated",
            "zolt package",
            "zolt platforms",
            "zolt platforms remove",
            "zolt platforms set",
            "zolt plan",
            "zolt policy",
            "zolt publish",
            "zolt quarkus",
            "zolt quarkus plan",
            "zolt quarkus test-plan",
            "zolt release-archive",
            "zolt release-index",
            "zolt release-verify",
            "zolt remove",
            "zolt resolve",
            "zolt run",
            "zolt run-package",
            "zolt sbom",
            "zolt self",
            "zolt self channel",
            "zolt self exec",
            "zolt self install",
            "zolt self prune",
            "zolt self releases",
            "zolt self rollback",
            "zolt self update",
            "zolt self use",
            "zolt self versions",
            "zolt self-check",
            "zolt self-parity",
            "zolt shims",
            "zolt shims install",
            "zolt shims status",
            "zolt shims uninstall",
            "zolt task",
            "zolt tasks",
            "zolt test",
            "zolt test plan",
            "zolt toolchain",
            "zolt toolchain available",
            "zolt toolchain global",
            "zolt toolchain global status",
            "zolt toolchain global unset",
            "zolt toolchain global use",
            "zolt toolchain global use java",
            "zolt toolchain install",
            "zolt toolchain install java",
            "zolt toolchain list",
            "zolt toolchain sync",
            "zolt toolchain status",
            "zolt tree",
            "zolt update",
            "zolt version",
            "zolt versions",
            "zolt versions remove",
            "zolt versions set",
            "zolt why",
            "zolt workspace",
            "zolt workspace members");

    @Test
    void allRegisteredCommandPathsHaveFocusedHelpSurfaceOwnership() {
        Set<String> registeredPaths = commandPaths(newCommandLine()).stream()
                .map(CliFocusedHelpSurfaceCoverageTest::commandPath)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(FOCUSED_HELP_SURFACE_PATHS, registeredPaths);
    }

    private static String commandPath(List<String> path) {
        return path.isEmpty() ? "zolt" : "zolt " + String.join(" ", path);
    }
}
