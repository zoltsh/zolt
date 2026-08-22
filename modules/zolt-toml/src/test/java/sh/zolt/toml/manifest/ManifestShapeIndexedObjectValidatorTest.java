package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FormattingPolicy;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestObjectShape;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSymbolRegistry;
import sh.zolt.toml.schema.ManifestValidationCategory;
import sh.zolt.toml.schema.ManifestValueKind;
import sh.zolt.toml.schema.MutationPolicy;

final class ManifestShapeIndexedObjectValidatorTest {
    private static final ManifestObjectMember GROUP =
            new ManifestObjectMember("group", ManifestValueKind.STRING, true, 10);
    private static final ManifestObjectMember MODULE =
            new ManifestObjectMember("module", ManifestValueKind.STRING, false, 20);
    private static final ManifestObjectMember COORDINATE =
            new ManifestObjectMember("coordinate", ManifestValueKind.STRING, false, 30);
    private static final ManifestObjectShape SHAPE = new ManifestObjectShape(
            List.of(GROUP, MODULE, COORDINATE),
            List.of(new ManifestObjectShape.PresenceGroup(
                    ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                    List.of(MODULE, COORDINATE))));
    private static final ManifestSchemaRegistry SCHEMA = new ManifestSchemaRegistry(
            List.of(new ManifestField(
                    ManifestPath.of("dependencies", "policy", "deny"),
                    ManifestValueKind.INLINE_TABLE_ARRAY,
                    FormattingPolicy.ONE_LINE,
                    MutationPolicy.NONE,
                    10,
                    Optional.empty(),
                    ManifestValidationCategory.NONE,
                    Map.of(),
                    Optional.of(SHAPE))),
            List.of(),
            new ManifestSymbolRegistry(List.of()));

    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator(SCHEMA);

    @Test
    void validatesEveryInlineObjectInTheArray() {
        validate("[{ group = \"org.first\", module = \"one\" }, "
                + "{ group = \"org.second\", coordinate = \"org.second:two\" }]");
    }

    @ParameterizedTest
    @MethodSource("indexedFailures")
    void anchorsEveryClosedObjectFailureToItsZeroBasedIndex(
            String value,
            String expected) {
        ZoltConfigException failure = failure(value);

        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertFalse(failure.getMessage().contains("deny.["), failure.getMessage());
    }

    static Stream<Arguments> indexedFailures() {
        String valid = "{ group = \"org.first\", module = \"one\" }";
        return Stream.of(
                Arguments.of(
                        "[{ group = \"org.first\", module = \"one\", gruop = \"typo\" }]",
                        "Unknown manifest field `dependencies.policy.deny[0].gruop`. "
                                + "Did you mean `dependencies.policy.deny[0].group`?"),
                Arguments.of(
                        "[" + valid + ", { module = \"two\" }]",
                        "Missing required inline-object field `dependencies.policy.deny[1].group`"),
                Arguments.of(
                        "[" + valid + ", " + valid + ", { group = 3, module = \"three\" }]",
                        "Invalid value for `dependencies.policy.deny[2].group`: "
                                + "expected string but found integer"),
                Arguments.of(
                        "[" + valid + ", { group = \"org.second\" }]",
                        "Inline object `dependencies.policy.deny[1]` must declare exactly one "
                                + "of `module` or `coordinate`"));
    }

    @Test
    void reportsTheFirstInvalidArrayItemBeforeLaterFailures() {
        ZoltConfigException failure = failure(
                "[{ module = \"first\" }, "
                        + "{ group = \"second\", module = \"two\", typo = \"later\" }]");

        assertTrue(failure.getMessage().contains(
                "Missing required inline-object field `dependencies.policy.deny[0].group`"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("deny.["), failure.getMessage());
    }

    private void validate(String value) {
        validator.validate(parser.parse("dependencies.policy.deny = " + value + "\n"));
    }

    private ZoltConfigException failure(String value) {
        return assertThrows(ZoltConfigException.class, () -> validate(value));
    }
}
