package sh.zolt.arch;

import static sh.zolt.arch.ContextFootprintBudgetSupport.readBudgets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.arch.ContextFootprintBudgetSupport.Budget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ContextFootprintBudgetParserTest {
    @Test
    void budgetFileParserReadsRootsAndThresholds(@TempDir Path tempDir) throws IOException {
        Path budgets = tempDir.resolve("budgets.txt");
        Files.writeString(budgets, """
                # rootPattern[|packageName]|maxFiles|maxLines
                apps/*/src/main/java|140|15000

                modules/*/src/test/java|120|12000
                modules/zolt-toml/src/main/java|sh.zolt.toml.manifest|75|9000
                """);

        assertEquals(
                List.of(
                        new Budget(Path.of("apps/*/src/main/java"), 140, 15000),
                        new Budget(Path.of("modules/*/src/test/java"), 120, 12000),
                        new Budget(
                                Path.of("modules/zolt-toml/src/main/java"),
                                Optional.of("sh.zolt.toml.manifest"),
                                75,
                                9000)),
                readBudgets(budgets));
    }

    @Test
    void budgetFileParserRejectsMalformedLines(@TempDir Path tempDir) throws IOException {
        Path budgets = tempDir.resolve("budgets.txt");
        Files.writeString(budgets, "apps/*/src/main/java|140\n");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> readBudgets(budgets));

        assertEquals("Invalid context footprint budget line: apps/*/src/main/java|140", exception.getMessage());
    }

    @Test
    void budgetFileParserRejectsDuplicateExactRules(@TempDir Path tempDir) throws IOException {
        Path budgets = tempDir.resolve("budgets.txt");
        Path relative = Path.of("modules/zolt-toml/src/main/java");
        Path absolute = RepositoryPaths.root().resolve(relative);
        Files.writeString(
                budgets,
                relative + "|sh.zolt.toml.manifest|75|9000\n"
                        + absolute + "|sh.zolt.toml.manifest|76|9001\n");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> readBudgets(budgets));

        assertEquals(
                "Duplicate context footprint budget rule: "
                        + "modules/zolt-toml/src/main/java|sh.zolt.toml.manifest",
                exception.getMessage());
    }

    @Test
    void budgetFileParserRejectsWildcardPackageOverrides(@TempDir Path tempDir)
            throws IOException {
        Path budgets = tempDir.resolve("budgets.txt");
        Files.writeString(
                budgets,
                "modules/*/src/main/java|sh.zolt.toml.manifest|75|9000\n");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> readBudgets(budgets));

        assertEquals(
                "Package-specific context footprint budgets require an exact root.",
                exception.getMessage());
    }
}
