package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.license.SpdxExpressionParser;
import sh.zolt.lockfile.ZoltLockfile;

final class CycloneDxSbomWriterTest extends SbomTestSupport {
    private final LockSbomAssembler assembler = new LockSbomAssembler();
    private final CycloneDxSbomWriter writer = new CycloneDxSbomWriter();

    @Test
    void writesGoldenBomForRequiredGraph() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A,
                        List.of("org.example:lib-b:2.0.0")),
                maven("org.example", "lib-b", "2.0.0", DependencyScope.RUNTIME, false, SHA_B, List.of()));

        SbomModel model = assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);

        String json = writer.write(model);
        assertEquals("""
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.5",
                  "serialNumber": "urn:uuid:d74fef8c-ed6a-5cc0-a022-2f94c4c91089",
                  "version": 1,
                  "metadata": {
                    "tools": [
                      {
                        "name": "zolt",
                        "version": "0.1.0-TEST"
                      }
                    ],
                    "component": {
                      "type": "application",
                      "bom-ref": "pkg:maven/com.example/demo@0.1.0?type=jar",
                      "group": "com.example",
                      "name": "demo",
                      "version": "0.1.0",
                      "purl": "pkg:maven/com.example/demo@0.1.0?type=jar"
                    }
                  },
                  "components": [
                    {
                      "type": "library",
                      "bom-ref": "pkg:maven/org.example/lib-a@1.0.0?type=jar",
                      "group": "org.example",
                      "name": "lib-a",
                      "version": "1.0.0",
                      "purl": "pkg:maven/org.example/lib-a@1.0.0?type=jar",
                      "scope": "required",
                      "hashes": [
                        {
                          "alg": "SHA-256",
                          "content": "1111111111111111111111111111111111111111111111111111111111111111"
                        }
                      ]
                    },
                    {
                      "type": "library",
                      "bom-ref": "pkg:maven/org.example/lib-b@2.0.0?type=jar",
                      "group": "org.example",
                      "name": "lib-b",
                      "version": "2.0.0",
                      "purl": "pkg:maven/org.example/lib-b@2.0.0?type=jar",
                      "scope": "required",
                      "hashes": [
                        {
                          "alg": "SHA-256",
                          "content": "2222222222222222222222222222222222222222222222222222222222222222"
                        }
                      ]
                    }
                  ],
                  "dependencies": [
                    {
                      "ref": "pkg:maven/com.example/demo@0.1.0?type=jar",
                      "dependsOn": ["pkg:maven/org.example/lib-a@1.0.0?type=jar"]
                    },
                    {
                      "ref": "pkg:maven/org.example/lib-a@1.0.0?type=jar",
                      "dependsOn": ["pkg:maven/org.example/lib-b@2.0.0?type=jar"]
                    },
                    {
                      "ref": "pkg:maven/org.example/lib-b@2.0.0?type=jar",
                      "dependsOn": []
                    }
                  ]
                }
                """, json);
        CycloneDxSchemaValidator.assertValid(json);
    }

    @Test
    void sourceDateEpochAddsTimestampButLeavesSerialAndEverythingElseUnchanged() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        Optional<String> withEpoch = SbomTimestamp.resolve(
                Optional.empty(), Map.of("SOURCE_DATE_EPOCH", "1577836800"), fixedClock());
        SbomModel timestamped = assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), withEpoch, TOOL_VERSION);
        SbomModel bare = assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);

        assertEquals("2020-01-01T00:00:00Z", timestamped.timestamp().orElseThrow());
        assertEquals(bare.serialNumber(), timestamped.serialNumber());
        // The only byte difference is the timestamp line; strip it and the outputs are identical.
        String stripped = writer.write(timestamped)
                .replace("    \"timestamp\": \"2020-01-01T00:00:00Z\",\n", "");
        assertEquals(writer.write(bare), stripped);
    }

    @Test
    void emitsExplicitTimestampAsTheFirstMetadataField() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));
        SbomModel model = assembler.assemble(
                config(),
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.of("2026-07-23T12:00:00Z"),
                TOOL_VERSION);

        assertTrue(writer.write(model).contains(
                "  \"metadata\": {\n    \"timestamp\": \"2026-07-23T12:00:00Z\",\n    \"tools\": ["));
    }

    @Test
    void emitsSpdxIdAndUnmappedNameUrlLicensesInComponents() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()),
                maven("org.example", "lib-b", "2.0.0", DependencyScope.COMPILE, false, SHA_B, List.of()));
        LicenseIndex index = new LicenseIndex(
                Map.of(
                        "org.example:lib-a:1.0.0", List.of(SbomLicense.spdx("Apache-2.0")),
                        "org.example:lib-b:2.0.0",
                        List.of(SbomLicense.unmapped(Optional.of("Weird License"), Optional.of("https://example.com/l")))),
                List.of());

        SbomModel model = assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION, index);
        String json = writer.write(model);

        assertTrue(json.contains(
                "      \"licenses\": [\n"
                        + "        {\n"
                        + "          \"license\": {\n"
                        + "            \"id\": \"Apache-2.0\"\n"
                        + "          }\n"
                        + "        }\n"
                        + "      ]"), json);
        assertTrue(json.contains(
                "      \"licenses\": [\n"
                        + "        {\n"
                        + "          \"license\": {\n"
                        + "            \"name\": \"Weird License\",\n"
                        + "            \"url\": \"https://example.com/l\"\n"
                        + "          }\n"
                        + "        }\n"
                        + "      ]"), json);
        CycloneDxSchemaValidator.assertValid(json);
    }

    @Test
    void emitsASoleExplicitExpressionAsCycloneDxExpressionChoice() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));
        SbomLicense expression = SbomLicense.expression(
                new SpdxExpressionParser().parse("MIT AND BSD-3-Clause"),
                Optional.of("MIT and BSD-3-Clause"),
                Optional.of("https://example.com/licenses"));
        LicenseIndex index = new LicenseIndex(
                Map.of("org.example:lib-a:1.0.0", List.of(expression)), List.of());

        String json = writer.write(assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION, index));

        assertTrue(json.contains(
                "      \"licenses\": [\n"
                        + "        {\n"
                        + "          \"expression\": \"MIT AND BSD-3-Clause\"\n"
                        + "        }\n"
                        + "      ]"), json);
        CycloneDxSchemaValidator.assertValid(json);
    }

    @Test
    void mixedExpressionAndLicenseRecordsUseNamedLicenseFallback() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));
        SbomLicense expression = SbomLicense.expression(
                new SpdxExpressionParser().parse("MIT AND BSD-3-Clause"),
                Optional.of("MIT and BSD-3-Clause"),
                Optional.of("https://example.com/licenses"));
        LicenseIndex index = new LicenseIndex(
                Map.of("org.example:lib-a:1.0.0", List.of(expression, SbomLicense.spdx("Apache-2.0"))), List.of());

        String json = writer.write(assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION, index));

        assertTrue(json.contains("\"id\": \"Apache-2.0\""), json);
        assertTrue(json.contains(
                "\"name\": \"MIT and BSD-3-Clause\",\n"
                        + "            \"url\": \"https://example.com/licenses\""), json);
        assertTrue(!json.contains("\"expression\": \"MIT AND BSD-3-Clause\""), json);
        CycloneDxSchemaValidator.assertValid(json);
    }

    @Test
    void emitsAuthoritativeRootLicenseFromConfig() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        SbomModel model = assembler.assemble(
                configWithLicense("Apache-2.0", ""),
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty());

        assertEquals(
                List.of(SbomLicense.spdx("Apache-2.0")),
                model.metadataComponent().licenses());
    }

    @Test
    void emitsAuthoritativeRootExpressionFromConfig() {
        ZoltLockfile lockfile = lockfile(Optional.of("sha256:demo-lock-fingerprint"));

        SbomModel model = assembler.assemble(
                configWithLicense("MIT AND BSD-3-Clause", ""),
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty());

        assertEquals(SbomLicenseStatus.SPDX_EXPRESSION,
                model.metadataComponent().licenses().getFirst().status());
        assertEquals("MIT AND BSD-3-Clause",
                model.metadataComponent().licenses().getFirst().label());
        assertTrue(writer.write(model).contains("\"expression\": \"MIT AND BSD-3-Clause\""));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
