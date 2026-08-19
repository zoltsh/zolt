package sh.zolt.manifest;

import java.util.Comparator;
import java.util.Map;

/** Immutable authored generated-tool declarations with reserved IDs enforced. */
public record AuthoredGeneratedTools(Map<LocalId, AuthoredGeneratedTool> declarations) {
    private static final LocalId OPENAPI = new LocalId("openapi");
    private static final LocalId PROTOBUF = new LocalId("protobuf");
    private static final LocalId PROJECT = new LocalId("project");

    public AuthoredGeneratedTools {
        declarations = ManifestModelValues.immutableSortedMap(
                declarations,
                Comparator.naturalOrder(),
                "Generated tool ID",
                "Generated tool declaration");
        for (Map.Entry<LocalId, AuthoredGeneratedTool> entry : declarations.entrySet()) {
            validateReservedId(entry.getKey(), entry.getValue());
        }
    }

    public static AuthoredGeneratedTools empty() {
        return new AuthoredGeneratedTools(Map.of());
    }

    private static void validateReservedId(LocalId id, AuthoredGeneratedTool tool) {
        if (id.equals(PROJECT)) {
            throw new IllegalArgumentException(
                    "Generated tool ID `project` is a pseudo-tool and cannot be declared.");
        }
        if (id.equals(OPENAPI) && !(tool instanceof AuthoredGeneratedTool.OpenApi)) {
            throw new IllegalArgumentException(
                    "Reserved generated tool ID `openapi` derives the OpenAPI tool kind.");
        }
        if (id.equals(PROTOBUF) && !(tool instanceof AuthoredGeneratedTool.Protobuf)) {
            throw new IllegalArgumentException(
                    "Reserved generated tool ID `protobuf` derives the Protobuf tool kind.");
        }
    }
}
