package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class OutdatedJsonRendererV2Test {
    private final OutdatedJsonRendererV2 renderer = new OutdatedJsonRendererV2();

    @Test
    void emitsCanonicalAutomationSnapshot() {
        UpdateTarget target = new UpdateTarget(
                UpdateTargetId.parse("zt1_vcc-lFhiR4a_S4Vab01gw0_gcPDgShIiT8IdjXa5MhM"),
                "apps/api/zolt.toml",
                "zolt.lock",
                OutdatedSurface.DEPENDENCY,
                "com.google.guava:guava",
                "[dependencies]",
                "33.3.1-jre",
                true,
                Optional.empty(),
                List.of());
        OutdatedCandidates candidates = new OutdatedCandidates(
                Optional.empty(),
                Optional.of("33.4.0-jre"),
                Optional.of("34.0.0-jre"),
                Optional.of("33.4.0-jre"),
                Optional.of(UpdateClass.MINOR),
                Optional.of("34.0.0-jre"),
                Optional.of(UpdateClass.MAJOR));
        OutdatedEntry entry = new OutdatedEntry(
                target,
                OutdatedStatus.UPDATE_AVAILABLE,
                candidates,
                Optional.of("central"),
                List.of(),
                List.of());
        OutdatedReport report = new OutdatedReport(
                List.of(new OutdatedScopeReport(
                        "apps/api",
                        "apps/api/zolt.toml",
                        "zolt.lock",
                        List.of(entry))),
                List.of());

        String expected = """
                {
                  "schemaVersion": 2,
                  "command": "outdated",
                  "status": "ok",
                  "diagnostics": [],
                  "scopes": [
                    {
                      "label": "apps/api",
                      "manifestPath": "apps/api/zolt.toml",
                      "lockfilePath": "zolt.lock",
                      "entries": [
                        {
                          "targetId": "zt1_vcc-lFhiR4a_S4Vab01gw0_gcPDgShIiT8IdjXa5MhM",
                          "updateable": true,
                          "updateBlocker": null,
                          "surface": "dependency",
                          "identifier": "com.google.guava:guava",
                          "section": "[dependencies]",
                          "current": "33.3.1-jre",
                          "status": "update-available",
                          "candidates": {
                            "patch": null,
                            "minor": "33.4.0-jre",
                            "major": "34.0.0-jre"
                          },
                          "selectedInMajor": "33.4.0-jre",
                          "selectedInMajorClass": "minor",
                          "selectedLatest": "34.0.0-jre",
                          "selectedLatestClass": "major",
                          "source": "central",
                          "governs": [],
                          "members": [],
                          "notes": []
                        }
                      ]
                    }
                  ],
                  "notes": []
                }
                """;

        assertEquals(expected, renderer.render(report));
    }

    @Test
    void reportsStableBlockerAndNullPolicyForGeneratedTools() {
        UpdateTarget target = target(
                OutdatedSurface.OPENAPI_TOOL,
                "org.openapitools:openapi-generator-cli",
                "[generated.openapiTool]",
                false,
                Optional.of(
                        "Literal generated-tool coordinate mutation is not supported; route the version through a [versions] alias."));
        OutdatedEntry entry = new OutdatedEntry(
                target,
                OutdatedStatus.UNKNOWN,
                OutdatedCandidates.none(),
                Optional.empty(),
                List.of(),
                List.of("No metadata."));
        OutdatedReport report = new OutdatedReport(
                List.of(new OutdatedScopeReport("demo", "zolt.toml", "zolt.lock", List.of(entry))),
                List.of());

        String json = renderer.render(report);

        assertTrue(json.contains("\"updateable\": false"));
        assertTrue(json.contains("\"updateBlocker\": \"Literal generated-tool coordinate mutation is not supported"));
        assertTrue(json.contains("\"patch\": null"));
        assertTrue(json.contains("\"source\": null"));
        assertEquals(json, renderer.render(report));
    }

    private static UpdateTarget target(
            OutdatedSurface surface,
            String identifier,
            String section,
            boolean updateable,
            Optional<String> blocker) {
        return new UpdateTarget(
                UpdateTargetId.create("zolt.toml", surface, section, identifier),
                "zolt.toml",
                "zolt.lock",
                surface,
                identifier,
                section,
                "7.11.0",
                updateable,
                blocker,
                List.of());
    }
}
