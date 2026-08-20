package sh.zolt.arch;

import static sh.zolt.arch.ArchitectureDiagnostics.describe;
import static sh.zolt.arch.ContextFootprintBudgetSupport.packageFootprints;
import static sh.zolt.arch.ContextFootprintBudgetSupport.readBudgets;
import static sh.zolt.arch.ContextFootprintBudgetSupport.budgetFor;
import static sh.zolt.arch.ContextFootprintBudgetSupport.violation;
import static sh.zolt.arch.ContextFootprintBudgetSupport.writeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.arch.ContextFootprintBudgetSupport.Budget;
import sh.zolt.arch.ContextFootprintBudgetSupport.PackageFootprint;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ContextFootprintBudgetTest {
    private static final Path BUDGETS =
            RepositoryPaths.appRoot().resolve("src/test/resources/sh/zolt/arch/context-footprint-budgets.txt");

    @Test
    void packageFootprintsStayWithinBudgets() throws IOException {
        List<Budget> budgets = readBudgets(BUDGETS);
        List<PackageFootprint> footprints = packageFootprints(budgets);
        List<String> violations = new ArrayList<>();
        for (PackageFootprint footprint : footprints) {
            Budget budget = budgetFor(footprint, budgets);
            if (footprint.files() > budget.maxFiles() || footprint.lines() > budget.maxLines()) {
                violations.add(violation(footprint, budget));
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Context footprint budget violations:\n"
                        + describe(violations)
                        + "\nSplit the package/root or tighten the budget once the footprint shrinks.");
    }

    @Test
    void scannerGroupsFilesByRootAndPackage(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        writeSource(sourceRoot.resolve("com/example/alpha/First.java"), "com.example.alpha", 3);
        writeSource(sourceRoot.resolve("com/example/alpha/Second.java"), "com.example.alpha", 5);
        writeSource(sourceRoot.resolve("com/example/beta/Beta.java"), "com.example.beta", 7);

        assertEquals(
                List.of(
                        new PackageFootprint(sourceRoot, "com.example.alpha", 2, 8),
                        new PackageFootprint(sourceRoot, "com.example.beta", 1, 7)),
                packageFootprints(List.of(new Budget(sourceRoot, 10, 20))));
    }

    @Test
    void scannerExpandsWildcardRootPatterns(@TempDir Path tempDir) throws IOException {
        Path alphaRoot = tempDir.resolve("modules/alpha/src/main/java");
        Path betaRoot = tempDir.resolve("modules/beta/src/main/java");
        writeSource(alphaRoot.resolve("com/example/Alpha.java"), "com.example.alpha", 3);
        writeSource(betaRoot.resolve("com/example/Beta.java"), "com.example.beta", 5);

        assertEquals(
                List.of(
                        new PackageFootprint(alphaRoot, "com.example.alpha", 1, 3),
                        new PackageFootprint(betaRoot, "com.example.beta", 1, 5)),
                packageFootprints(List.of(new Budget(
                        tempDir.resolve("modules/*/src/main/java"),
                        10,
                        20))));
    }

    @Test
    void violationsReportFileAndLineBudgets() {
        PackageFootprint footprint = new PackageFootprint(
                Path.of("apps/zolt/src/test/java"), "sh.zolt.cli", 141, 15001);
        Budget budget = new Budget(Path.of("apps/*/src/test/java"), 140, 15000);

        assertEquals(
                "apps/zolt/src/test/java sh.zolt.cli has 141 files and 15001 lines; budget is 140 files and 15000 lines",
                violation(footprint, budget));
    }

    @Test
    void exactRootAndPackageOverrideWinsRegardlessOfRuleOrder(@TempDir Path tempDir)
            throws IOException {
        Path sourceRoot = tempDir.resolve("modules/zolt-toml/main-java");
        Path neighborRoot = tempDir.resolve("modules/zolt-toml/test-java");
        writeSource(
                sourceRoot.resolve("sh/zolt/toml/manifest/Decoder.java"),
                "sh.zolt.toml.manifest",
                7);
        writeSource(
                sourceRoot.resolve("sh/zolt/toml/schema/Schema.java"),
                "sh.zolt.toml.schema",
                5);
        writeSource(
                neighborRoot.resolve("sh/zolt/toml/manifest/DecoderTest.java"),
                "sh.zolt.toml.manifest",
                4);
        Budget generic = new Budget(
                tempDir.resolve("modules/*/main-java"), 75, 6500);
        Budget neighborGeneric = new Budget(
                tempDir.resolve("modules/*/test-java"), 70, 7500);
        Budget override = new Budget(
                sourceRoot,
                java.util.Optional.of("sh.zolt.toml.manifest"),
                75,
                9000);
        List<PackageFootprint> footprints = packageFootprints(
                List.of(generic, neighborGeneric, override));
        PackageFootprint manifest = footprints.stream()
                .filter(footprint -> footprint.sourceRoot().equals(
                        sourceRoot.toAbsolutePath().normalize()))
                .filter(footprint -> footprint.packageName().equals("sh.zolt.toml.manifest"))
                .findFirst()
                .orElseThrow();
        PackageFootprint schema = footprints.stream()
                .filter(footprint -> footprint.packageName().equals("sh.zolt.toml.schema"))
                .findFirst()
                .orElseThrow();
        PackageFootprint neighbor = footprints.stream()
                .filter(footprint -> footprint.sourceRoot().equals(
                        neighborRoot.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow();

        assertSame(override, budgetFor(manifest, List.of(generic, override)));
        assertSame(override, budgetFor(manifest, List.of(override, generic)));
        assertSame(generic, budgetFor(schema, List.of(override, generic)));
        assertSame(
                neighborGeneric,
                budgetFor(neighbor, List.of(override, generic, neighborGeneric)));
        assertEquals(3, footprints.size());
    }

    @Test
    void selectorFailsClosedOnAmbiguousGenericOrExactRules(@TempDir Path tempDir)
            throws IOException {
        Path sourceRoot = tempDir.resolve("modules/zolt-toml/main-java");
        writeSource(
                sourceRoot.resolve("sh/zolt/toml/manifest/Decoder.java"),
                "sh.zolt.toml.manifest",
                3);
        PackageFootprint footprint = packageFootprints(
                        List.of(new Budget(sourceRoot, 75, 6500)))
                .getFirst();
        Budget wildcard = new Budget(
                tempDir.resolve("modules/*/main-java"), 75, 6500);
        Budget exactGeneric = new Budget(sourceRoot, 75, 6500);
        Budget override = new Budget(
                sourceRoot,
                java.util.Optional.of("sh.zolt.toml.manifest"),
                75,
                9000);

        assertThrows(
                IllegalStateException.class,
                () -> budgetFor(footprint, List.of(wildcard, exactGeneric)));
        assertThrows(
                IllegalStateException.class,
                () -> budgetFor(footprint, List.of(override, override, wildcard)));
        assertThrows(
                IllegalStateException.class,
                () -> budgetFor(footprint, List.of(override)));
        assertThrows(
                IllegalStateException.class,
                () -> budgetFor(
                        footprint,
                        List.of(override, wildcard, exactGeneric)));
    }

    @Test
    void stalePackageOverridesFailForWrongRootOrPackage(@TempDir Path tempDir)
            throws IOException {
        Path sourceRoot = tempDir.resolve("modules/zolt-toml/main-java");
        writeSource(
                sourceRoot.resolve("sh/zolt/toml/manifest/Decoder.java"),
                "sh.zolt.toml.manifest",
                3);
        Budget generic = new Budget(
                tempDir.resolve("modules/*/main-java"), 75, 6500);
        Budget wrongRoot = new Budget(
                tempDir.resolve("modules/other/main-java"),
                java.util.Optional.of("sh.zolt.toml.manifest"),
                75,
                9000);
        Budget wrongPackage = new Budget(
                sourceRoot,
                java.util.Optional.of("sh.zolt.toml.mnaifest"),
                75,
                9000);

        assertThrows(
                IllegalStateException.class,
                () -> packageFootprints(List.of(generic, wrongRoot)));
        assertThrows(
                IllegalStateException.class,
                () -> packageFootprints(List.of(generic, wrongPackage)));
    }

}
