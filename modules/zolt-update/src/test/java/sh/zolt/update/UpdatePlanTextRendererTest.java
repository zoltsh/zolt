package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UpdatePlanTextRendererTest {
    private final UpdatePlanTextRenderer renderer = new UpdatePlanTextRenderer();

    @Test
    void rendersEditsSkipsAndWarnings() {
        UpdateEdit edit = new UpdateEdit(
                OutdatedSurface.VERSION_ALIAS,
                "guava",
                "[versions]",
                "30.0-jre",
                "33.0-jre",
                UpdateClass.MAJOR,
                List.of("[dependencies].com.google.guava:guava"));
        UpdateSkip skip = new UpdateSkip(
                OutdatedSurface.EXEC_TOOL_COORDINATE,
                "com.tool:cli",
                "[generated.tools.codegen]",
                "route it through a [versions] alias.");
        UpdatePlan plan = new UpdatePlan(List.of(edit), List.of(skip), List.of("alias fan-out warning"));

        String text = renderer.render(plan, false);
        assertTrue(text.contains("Updated:"));
        assertTrue(text.contains("[versions].guava  30.0-jre -> 33.0-jre  (major)"));
        assertTrue(text.contains("Skipped:"));
        assertTrue(text.contains("[generated.tools.codegen].com.tool:cli"));
        assertTrue(text.contains("route it through a [versions] alias."));
        assertTrue(text.contains("warning: alias fan-out warning"));
    }

    @Test
    void dryRunUsesPlannedHeader() {
        UpdateEdit edit = new UpdateEdit(
                OutdatedSurface.DEPENDENCY,
                "com.example:lib",
                "[dependencies]",
                "1.0.0",
                "1.1.0",
                UpdateClass.MINOR,
                List.of());
        String text = renderer.render(new UpdatePlan(List.of(edit), List.of(), List.of()), true);
        assertTrue(text.contains("Planned updates (dry run):"));
    }

    @Test
    void reportsUpToDateWhenNothingToDo() {
        assertEquals(
                "Everything is up to date.\n",
                renderer.render(new UpdatePlan(List.of(), List.of(), List.of()), false));
    }
}
