package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ExactUpdateJsonRendererTest {
    private final ExactUpdateJsonRenderer renderer = new ExactUpdateJsonRenderer();

    @Test
    void rendersAppliedDependencyWithActualChangedFiles() {
        UpdateTarget target = target(
                OutdatedSurface.DEPENDENCY,
                "com.example:lib",
                "[dependencies]",
                "1.2.3",
                List.of());
        ExactUpdatePlan plan = new ExactUpdatePlan(
                target, "1.2.3", "1.4.0", Optional.of(UpdateClass.MINOR), true, List.of());
        ExactUpdateResult result = new ExactUpdateResult(
                plan, false, true, true, List.of("apps/api/zolt.toml", "zolt.lock"));

        String expected = """
                {
                  "schemaVersion": 2,
                  "command": "update",
                  "status": "ok",
                  "dryRun": false,
                  "target": {
                    "targetId": "%s",
                    "manifestPath": "apps/api/zolt.toml",
                    "lockfilePath": "zolt.lock",
                    "surface": "dependency",
                    "identifier": "com.example:lib",
                    "section": "[dependencies]",
                    "updateable": true
                  },
                  "from": "1.2.3",
                  "to": "1.4.0",
                  "class": "minor",
                  "changed": true,
                  "applied": true,
                  "resolved": true,
                  "changedFiles": [
                    "apps/api/zolt.toml",
                    "zolt.lock"
                  ],
                  "fanOut": [],
                  "diagnostics": []
                }
                """.formatted(target.targetId());

        assertEquals(expected, renderer.render(result));
    }

    @Test
    void rendersDryRunAndSameVersionNoOpSemantics() {
        UpdateTarget target = target(
                OutdatedSurface.DEPENDENCY,
                "com.example:lib",
                "[dependencies]",
                "1.2.3",
                List.of());
        ExactUpdateResult dryRun = new ExactUpdateResult(
                new ExactUpdatePlan(
                        target, "1.2.3", "2.0.0", Optional.of(UpdateClass.MAJOR), true, List.of()),
                true,
                false,
                false,
                List.of());
        ExactUpdateResult noOp = new ExactUpdateResult(
                new ExactUpdatePlan(target, "1.2.3", "1.2.3", Optional.empty(), false, List.of()),
                false,
                false,
                false,
                List.of());

        String dryRunJson = renderer.render(dryRun);
        String noOpJson = renderer.render(noOp);

        assertTrue(dryRunJson.contains("\"dryRun\": true"));
        assertTrue(dryRunJson.contains("\"changed\": true"));
        assertTrue(dryRunJson.contains("\"applied\": false"));
        assertTrue(noOpJson.contains("\"class\": null"));
        assertTrue(noOpJson.contains("\"changed\": false"));
        assertTrue(noOpJson.contains("\"changedFiles\": []"));
    }

    @Test
    void rendersAliasFanOutAsDataAndWarningDiagnostic() {
        UpdateTarget target = target(
                OutdatedSurface.VERSION_ALIAS,
                "shared",
                "[versions]",
                "1.0.0",
                List.of("[dependencies].com.example:one", "[dependencies.test].com.example:two"));
        String warning = "Alias `shared` affects two coordinates.";
        ExactUpdateResult result = new ExactUpdateResult(
                new ExactUpdatePlan(
                        target, "1.0.0", "2.0.0", Optional.of(UpdateClass.MAJOR), true, List.of(warning)),
                false,
                true,
                false,
                List.of("apps/api/zolt.toml"));

        String json = renderer.render(result);

        assertTrue(json.contains("\"fanOut\": [\n    \"[dependencies].com.example:one\""));
        assertTrue(json.contains("{\"severity\": \"warning\", \"message\": \"" + warning + "\"}"));
    }

    private static UpdateTarget target(
            OutdatedSurface surface,
            String identifier,
            String section,
            String current,
            List<String> governs) {
        return new UpdateTarget(
                UpdateTargetId.create("apps/api/zolt.toml", surface, section, identifier),
                "apps/api/zolt.toml",
                "zolt.lock",
                surface,
                identifier,
                section,
                current,
                true,
                Optional.empty(),
                governs);
    }
}
