package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UpdatePlanJsonRendererTest {
    private final UpdatePlanJsonRenderer renderer = new UpdatePlanJsonRenderer();

    @Test
    void emitsEditsAndWarningsSnapshot() {
        UpdateEdit edit = new UpdateEdit(
                OutdatedSurface.VERSION_ALIAS,
                "guava",
                "[versions]",
                "30.0-jre",
                "33.0-jre",
                UpdateClass.MAJOR,
                List.of("[dependencies].com.google.guava:guava"));
        String warning =
                "Alias `guava` 30.0-jre -> 33.0-jre updates 1 referencing coordinate(s): "
                        + "[dependencies].com.google.guava:guava.";
        UpdatePlan plan = new UpdatePlan(List.of(edit), List.of(), List.of(warning));

        String expected = String.join(
                        "\n",
                        "{",
                        "  \"schemaVersion\": 1,",
                        "  \"command\": \"update\",",
                        "  \"status\": \"ok\",",
                        "  \"dryRun\": true,",
                        "  \"edits\": [",
                        "    {",
                        "      \"surface\": \"versionAlias\",",
                        "      \"identifier\": \"guava\",",
                        "      \"section\": \"[versions]\",",
                        "      \"from\": \"30.0-jre\",",
                        "      \"to\": \"33.0-jre\",",
                        "      \"class\": \"major\",",
                        "      \"fanOut\": [",
                        "        \"[dependencies].com.google.guava:guava\"",
                        "      ]",
                        "    }",
                        "  ],",
                        "  \"skipped\": [],",
                        "  \"diagnostics\": [",
                        "    {\"severity\": \"warning\", \"message\": \"" + warning + "\"}",
                        "  ],",
                        "  \"warnings\": [",
                        "    \"" + warning + "\"",
                        "  ]",
                        "}")
                + "\n";

        assertEquals(expected, renderer.render(plan, true));
    }

    @Test
    void emitsSkippedEntries() {
        UpdateSkip skip = new UpdateSkip(
                OutdatedSurface.EXEC_TOOL_COORDINATE,
                "com.tool:cli",
                "[generated.execTools.codegen]",
                "not supported");
        UpdatePlan plan = new UpdatePlan(List.of(), List.of(skip), List.of());

        String json = renderer.render(plan, false);
        assertTrue(json.contains("\"dryRun\": false"));
        assertTrue(json.contains("\"edits\": []"));
        assertTrue(json.contains("\"surface\": \"execToolCoordinate\""));
        assertTrue(json.contains("\"reason\": \"not supported\""));
    }
}
