package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Offline validation against the source-pinned CycloneDX 1.5 JSON schemas. */
final class CycloneDxSchemaValidator {
    private static final String BASE = "http://cyclonedx.org/schema/";
    private static final String ROOT = BASE + "bom-1.5.schema.json";
    private static final Schema SCHEMA = schema();

    private CycloneDxSchemaValidator() {
    }

    static void assertValid(String json) {
        List<Error> errors = SCHEMA.validate(json, InputFormat.JSON);
        assertTrue(errors.isEmpty(), () -> "CycloneDX 1.5 schema errors: " + errors + "\n" + json);
    }

    private static Schema schema() {
        Map<String, String> schemas = Map.of(
                ROOT, resource("bom-1.5.schema.json"),
                BASE + "spdx.schema.json", resource("spdx.schema.json"),
                BASE + "jsf-0.82.schema.json", resource("jsf-0.82.schema.json"));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_7,
                builder -> builder
                        .schemaLoader(loader -> loader.fetchRemoteResources(false))
                        .schemas(schemas));
        return registry.getSchema(SchemaLocation.of(ROOT));
    }

    private static String resource(String name) {
        String path = "/cyclonedx-1.5/" + name;
        try (InputStream stream = CycloneDxSchemaValidator.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing pinned CycloneDX schema " + path + ".");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read pinned CycloneDX schema " + path + ".", exception);
        }
    }
}
