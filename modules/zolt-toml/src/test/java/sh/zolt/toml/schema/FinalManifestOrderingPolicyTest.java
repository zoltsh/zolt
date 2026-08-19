package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class FinalManifestOrderingPolicyTest extends FinalManifestSchemaTestSupport {
    @Test
    void recordsDependencyFieldsInTheirFrozenDomainOrder() {
        assertEquals(
                List.of(
                        5_001,
                        5_011,
                        5_021,
                        5_031,
                        5_041,
                        5_051,
                        5_061,
                        5_071,
                        5_081,
                        5_091,
                        5_092,
                        5_101,
                        5_102,
                        5_103,
                        5_111,
                        5_112,
                        5_113),
                registry.fields().stream()
                        .filter(field -> field.path().toString().startsWith("dependencies."))
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void recordsBuildAndTestFieldsInTheirFrozenDomainOrder() {
        assertEquals(
                List.of(
                        6_001,
                        6_011,
                        6_012,
                        6_013,
                        6_014,
                        6_021,
                        6_022,
                        6_023,
                        6_101,
                        6_102,
                        6_103,
                        6_111,
                        6_112,
                        6_121,
                        6_122,
                        6_201,
                        6_202,
                        6_211,
                        6_212,
                        6_213,
                        6_221,
                        6_701,
                        6_702,
                        6_711,
                        6_712,
                        6_713,
                        6_714,
                        6_721,
                        6_722,
                        6_731,
                        6_732,
                        6_733,
                        6_734,
                        6_735,
                        6_736),
                registry.fields().stream()
                        .filter(field -> {
                            String path = field.path().toString();
                            return path.startsWith("build.")
                                    || path.startsWith("compiler.")
                                    || path.startsWith("resources.")
                                    || path.startsWith("test.");
                        })
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void limitsOneLineMutationToFrozenMutableMaps() {
        assertEquals(
                Set.of(
                        "workspace.project.license",
                        "project.license",
                        "versions.<id>",
                        "platforms.<coordinate>",
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>",
                        "dependencies.constraints.<coordinate>",
                        "resources.tokens.<id>",
                        "bom.versions.<coordinate>",
                        "bom.imports.<coordinate>"),
                registry.fields().stream()
                        .filter(field -> field.formatting() == FormattingPolicy.ONE_LINE)
                        .map(field -> field.path().toString())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of(
                        "versions.<id>",
                        "platforms.<coordinate>",
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>",
                        "dependencies.constraints.<coordinate>",
                        "bom.versions.<coordinate>",
                        "bom.imports.<coordinate>"),
                registry.fields().stream()
                        .filter(field -> field.mutation() == MutationPolicy.REPLACE_ENTRY)
                        .map(field -> field.path().toString())
                        .collect(Collectors.toSet()));
        assertTrue(registry.fields().stream()
                .filter(field -> field.mutation() == MutationPolicy.NONE)
                .noneMatch(field -> field.path().toString().equals("versions.<id>")
                        || field.path().toString().equals("platforms.<coordinate>")));
        assertTrue(registry.fields().stream()
                .noneMatch(field -> field.mutation() == MutationPolicy.REPLACE_VALUE));
        assertEquals(FormattingPolicy.DEFAULT, field("dependencies.policy.conflicts").formatting());
        assertEquals(MutationPolicy.NONE, field("dependencies.policy.conflicts").mutation());
        assertEquals(FormattingPolicy.DEFAULT, field("dependencies.policy.deny").formatting());
        assertEquals(MutationPolicy.NONE, field("dependencies.policy.deny").mutation());
        assertEquals(
                FormattingPolicy.DEFAULT,
                field("dependencies.license-exceptions.<coordinate>.allow").formatting());
        assertEquals(
                MutationPolicy.NONE,
                field("dependencies.license-exceptions.<coordinate>.allow").mutation());
        assertEquals(FormattingPolicy.ONE_LINE, field("resources.tokens.<id>").formatting());
        assertEquals(MutationPolicy.NONE, field("resources.tokens.<id>").mutation());
        assertEquals(FormattingPolicy.DEFAULT, field("test.suites.<id>.locks").formatting());
        assertEquals(MutationPolicy.NONE, field("test.suites.<id>.locks").mutation());
        assertEquals(FormattingPolicy.ONE_LINE, field("bom.versions.<coordinate>").formatting());
        assertEquals(MutationPolicy.REPLACE_ENTRY, field("bom.versions.<coordinate>").mutation());
        assertEquals(FormattingPolicy.ONE_LINE, field("bom.imports.<coordinate>").formatting());
        assertEquals(MutationPolicy.REPLACE_ENTRY, field("bom.imports.<coordinate>").mutation());
    }
}
