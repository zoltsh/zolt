package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedProcessBinary;
import sh.zolt.manifest.GeneratedVersionExpectation;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;

final class AuthoredGeneratedToolsTest {
    @Test
    void retainsEveryToolKindInCanonicalIdOrder() {
        LinkedHashMap<LocalId, AuthoredGeneratedTool> source = new LinkedHashMap<>();
        source.put(new LocalId("z-process"), process("npm"));
        source.put(new LocalId("protobuf"), protobuf());
        source.put(new LocalId("jooq"), jvmTool());
        source.put(new LocalId("openapi"), openApi());

        AuthoredGeneratedTools tools = new AuthoredGeneratedTools(source);
        source.clear();

        assertEquals(
                List.of("jooq", "openapi", "protobuf", "z-process"),
                tools.declarations().keySet().stream().map(LocalId::value).toList());
        assertInstanceOf(
                AuthoredGeneratedTool.Jvm.class,
                tools.declarations().get(new LocalId("jooq")));
        assertThrows(UnsupportedOperationException.class, () -> tools.declarations().clear());
    }

    @Test
    void reservesBuiltInAndProjectToolIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTools(Map.of(new LocalId("project"), openApi())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTools(Map.of(new LocalId("openapi"), protobuf())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTools(Map.of(new LocalId("protobuf"), openApi())));

        assertEquals(
                openApi(),
                new AuthoredGeneratedTools(Map.of(new LocalId("custom-openapi"), openApi()))
                        .declarations().get(new LocalId("custom-openapi")));
    }

    @Test
    void jvmToolRequiresDistinctImmutableArtifactRequests() {
        ArrayList<GeneratedArtifactRequest> requests = new ArrayList<>();
        requests.add(request("org.jooq:jooq-codegen", "jooq"));
        AuthoredGeneratedTool.Jvm tool = new AuthoredGeneratedTool.Jvm(
                requests, new JavaBinaryClassName("org.jooq.codegen.GenerationTool"));
        requests.clear();

        assertEquals(1, tool.coordinates().size());
        assertThrows(UnsupportedOperationException.class, () -> tool.coordinates().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTool.Jvm(
                        List.of(request("org.jooq:jooq-codegen", "a"),
                                request("org.jooq:jooq-codegen", "b")),
                        new JavaBinaryClassName("org.jooq.codegen.GenerationTool")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedArtifactRequest(
                        new DependencyCoordinate("org.jooq:jooq-codegen"),
                        new DependencySelector.Managed()));
    }

    private static AuthoredGeneratedTool.OpenApi openApi() {
        return new AuthoredGeneratedTool.OpenApi(
                Optional.empty(),
                Optional.of(new DependencySelector.VersionReference(new LocalId("openapi"))));
    }

    private static AuthoredGeneratedTool.Protobuf protobuf() {
        return new AuthoredGeneratedTool.Protobuf(
                Optional.empty(),
                Optional.of(new DependencySelector.VersionReference(new LocalId("protobuf"))),
                Optional.empty(),
                Optional.of(new DependencySelector.VersionReference(new LocalId("grpc"))));
    }

    private static AuthoredGeneratedTool.Jvm jvmTool() {
        return new AuthoredGeneratedTool.Jvm(
                List.of(request("org.jooq:jooq-codegen", "jooq")),
                new JavaBinaryClassName("org.jooq.codegen.GenerationTool"));
    }

    private static AuthoredGeneratedTool.Process process(String binary) {
        return new AuthoredGeneratedTool.Process(
                new GeneratedProcessBinary(binary),
                List.of(binary, "--version"),
                Optional.of(new GeneratedVersionExpectation(">=10 <11")),
                true);
    }

    private static GeneratedArtifactRequest request(String coordinate, String alias) {
        return new GeneratedArtifactRequest(
                new DependencyCoordinate(coordinate),
                new DependencySelector.VersionReference(new LocalId(alias)));
    }
}
