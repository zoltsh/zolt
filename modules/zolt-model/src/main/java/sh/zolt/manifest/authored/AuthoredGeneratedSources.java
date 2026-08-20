package sh.zolt.manifest.authored;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;

/** Complete authored generated-source domain with local references resolved by kind. */
public record AuthoredGeneratedSources(
        AuthoredGeneratedTools tools,
        AuthoredGeneratedPresets presets,
        Map<LocalId, AuthoredGeneratedStep> main,
        Map<LocalId, AuthoredGeneratedStep> test) {
    private static final LocalId OPENAPI = new LocalId("openapi");
    private static final LocalId PROTOBUF = new LocalId("protobuf");
    private static final LocalId PROJECT = new LocalId("project");

    public AuthoredGeneratedSources {
        Objects.requireNonNull(tools, "Authored generated tools must not be null.");
        Objects.requireNonNull(presets, "Authored generated presets must not be null.");
        main = immutableSteps(main, "Main generated step");
        test = immutableSteps(test, "Test generated step");
        validateReferences(tools, presets, main);
        validateReferences(tools, presets, test);
    }

    public static AuthoredGeneratedSources empty() {
        return new AuthoredGeneratedSources(
                AuthoredGeneratedTools.empty(),
                AuthoredGeneratedPresets.empty(),
                Map.of(),
                Map.of());
    }

    private static Map<LocalId, AuthoredGeneratedStep> immutableSteps(
            Map<LocalId, AuthoredGeneratedStep> values, String label) {
        return ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                label + " ID",
                label);
    }

    private static void validateReferences(
            AuthoredGeneratedTools tools,
            AuthoredGeneratedPresets presets,
            Map<LocalId, AuthoredGeneratedStep> steps) {
        steps.forEach((id, step) -> {
            if (step instanceof AuthoredOpenApiStep openApi) {
                validateTool(id, openApi.tool().orElse(OPENAPI), tools, AuthoredGeneratedTool.OpenApi.class);
                openApi.preset().ifPresent(preset -> {
                    if (!presets.openApi().containsKey(preset)) {
                        throw new IllegalArgumentException(
                                "Generated OpenAPI step `" + id
                                        + "` references undefined preset `" + preset + "`.");
                    }
                });
            } else if (step instanceof AuthoredProtobufStep protobuf) {
                validateTool(
                        id,
                        protobuf.tool().orElse(PROTOBUF),
                        tools,
                        AuthoredGeneratedTool.Protobuf.class);
            } else if (step instanceof AuthoredExecStep exec && !exec.tool().equals(PROJECT)) {
                validateExecTool(id, exec.tool(), tools);
            }
        });
    }

    private static void validateTool(
            LocalId step,
            LocalId tool,
            AuthoredGeneratedTools tools,
            Class<? extends AuthoredGeneratedTool> expectedKind) {
        AuthoredGeneratedTool declaration = tools.declarations().get(tool);
        boolean implicitBuiltIn = tool.equals(OPENAPI) || tool.equals(PROTOBUF);
        if (declaration == null && implicitBuiltIn) {
            if ((tool.equals(OPENAPI) && expectedKind == AuthoredGeneratedTool.OpenApi.class)
                    || (tool.equals(PROTOBUF)
                            && expectedKind == AuthoredGeneratedTool.Protobuf.class)) {
                return;
            }
        }
        if (declaration == null || !expectedKind.isInstance(declaration)) {
            throw new IllegalArgumentException(
                    "Generated step `" + step + "` requires `" + tool + "` to be a declared "
                            + kindName(expectedKind) + " tool.");
        }
    }

    private static void validateExecTool(
            LocalId step, LocalId tool, AuthoredGeneratedTools tools) {
        AuthoredGeneratedTool declaration = tools.declarations().get(tool);
        if (!(declaration instanceof AuthoredGeneratedTool.Jvm)
                && !(declaration instanceof AuthoredGeneratedTool.Process)) {
            throw new IllegalArgumentException(
                    "Generated exec step `" + step
                            + "` requires `" + tool + "` to be a declared JVM or process tool.");
        }
    }

    private static String kindName(Class<? extends AuthoredGeneratedTool> kind) {
        return kind == AuthoredGeneratedTool.OpenApi.class ? "OpenAPI" : "Protobuf";
    }
}
