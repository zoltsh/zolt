package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class OutdatedJsonRendererTest {
    private final OutdatedJsonRenderer renderer = new OutdatedJsonRenderer();

    @Test
    void emitsSchemaV1Snapshot() {
        OutdatedCandidates candidates = new OutdatedCandidates(
                Optional.of("1.0.1"),
                Optional.of("1.1.0"),
                Optional.of("2.0.0"),
                Optional.of("1.1.0"),
                Optional.of(UpdateClass.MINOR),
                Optional.of("2.0.0"),
                Optional.of(UpdateClass.MAJOR));
        OutdatedEntry entry = new OutdatedEntry(
                OutdatedSurface.DEPENDENCY,
                "com.example:lib",
                "[dependencies]",
                "1.0.0",
                OutdatedStatus.UPDATE_AVAILABLE,
                candidates,
                Optional.of("central"),
                List.of(),
                List.of(),
                List.of());
        OutdatedReport report =
                new OutdatedReport(List.of(new OutdatedScopeReport("demo", List.of(entry))), List.of());

        String expected = String.join(
                        "\n",
                        "{",
                        "  \"schemaVersion\": 1,",
                        "  \"command\": \"outdated\",",
                        "  \"status\": \"ok\",",
                        "  \"diagnostics\": [],",
                        "  \"scopes\": [",
                        "    {",
                        "      \"label\": \"demo\",",
                        "      \"entries\": [",
                        "        {",
                        "          \"surface\": \"dependency\",",
                        "          \"identifier\": \"com.example:lib\",",
                        "          \"section\": \"[dependencies]\",",
                        "          \"current\": \"1.0.0\",",
                        "          \"status\": \"update-available\",",
                        "          \"candidates\": {",
                        "            \"patch\": \"1.0.1\",",
                        "            \"minor\": \"1.1.0\",",
                        "            \"major\": \"2.0.0\"",
                        "          },",
                        "          \"selectedInMajor\": \"1.1.0\",",
                        "          \"selectedInMajorClass\": \"minor\",",
                        "          \"selectedLatest\": \"2.0.0\",",
                        "          \"selectedLatestClass\": \"major\",",
                        "          \"source\": \"central\",",
                        "          \"governs\": [],",
                        "          \"members\": [],",
                        "          \"notes\": []",
                        "        }",
                        "      ]",
                        "    }",
                        "  ],",
                        "  \"notes\": []",
                        "}")
                + "\n";

        assertEquals(expected, renderer.render(report));
    }

    @Test
    void absentValuesAreNullNotOmitted() {
        OutdatedEntry entry = new OutdatedEntry(
                OutdatedSurface.DEPENDENCY,
                "com.example:lib",
                "[dependencies]",
                "9.9.9",
                OutdatedStatus.UNKNOWN,
                OutdatedCandidates.none(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of("Offline: no cached version listing."));
        OutdatedReport report =
                new OutdatedReport(List.of(new OutdatedScopeReport("demo", List.of(entry))), List.of());

        String json = renderer.render(report);
        assertTrue(json.contains("\"patch\": null"));
        assertTrue(json.contains("\"minor\": null"));
        assertTrue(json.contains("\"major\": null"));
        assertTrue(json.contains("\"selectedInMajor\": null"));
        assertTrue(json.contains("\"selectedLatest\": null"));
        assertTrue(json.contains("\"source\": null"));
        assertTrue(json.contains("\"status\": \"unknown\""));
    }

    @Test
    void renderingIsDeterministic() {
        OutdatedReport report = new OutdatedReport(
                List.of(new OutdatedScopeReport(
                        "demo",
                        List.of(new OutdatedEntry(
                                OutdatedSurface.VERSION_ALIAS,
                                "guava",
                                "[versions]",
                                "33.4.0-jre",
                                OutdatedStatus.UPDATE_AVAILABLE,
                                OutdatedCandidates.none(),
                                Optional.empty(),
                                List.of("[dependencies].com.google.guava:guava"),
                                List.of(),
                                List.of())))),
                List.of());

        assertEquals(renderer.render(report), renderer.render(report));
    }
}
