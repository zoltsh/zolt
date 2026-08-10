package sh.zolt.toolchain.catalog.metadata;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class MetadataJson {
    private static final JsonFactory JSON = JsonFactory.builder().build();

    private MetadataJson() {
    }

    static Object parse(String content, String provider) {
        try (JsonParser parser = JSON.createParser(content)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw malformed(provider, null);
            }
            Object value = read(parser, token);
            if (parser.nextToken() != null) {
                throw malformed(provider, null);
            }
            return value;
        } catch (IOException | IllegalStateException exception) {
            throw malformed(provider, exception);
        }
    }

    static Map<String, Object> object(Object value, String provider) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw malformed(provider, null);
        }
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        raw.forEach((key, nested) -> object.put(String.valueOf(key), nested));
        return object;
    }

    static List<Object> array(Object value, String provider) {
        if (!(value instanceof List<?> raw)) {
            throw malformed(provider, null);
        }
        return List.copyOf(raw);
    }

    static String requiredString(Map<String, Object> object, String field, String provider) {
        return optionalString(object, field).orElseThrow(() -> malformed(provider, null));
    }

    static Optional<String> optionalString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return Optional.empty();
        }
        String string = value instanceof String text ? text : String.valueOf(value);
        return string.isBlank() ? Optional.empty() : Optional.of(string.strip());
    }

    static boolean booleanValue(Map<String, Object> object, String field) {
        Object value = object.get(field);
        return value instanceof Boolean bool && bool;
    }

    private static Object read(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getNumberValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalStateException("Unexpected JSON token " + token);
        };
    }

    private static Map<String, Object> readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token != JsonToken.FIELD_NAME) {
                throw new IllegalStateException("Expected a JSON field name");
            }
            String name = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            object.put(name, read(parser, valueToken));
        }
        return object;
    }

    private static List<Object> readArray(JsonParser parser) throws IOException {
        ArrayList<Object> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            values.add(read(parser, token));
        }
        return List.copyOf(values);
    }

    private static ActionableException malformed(String provider, Throwable cause) {
        return new ActionableException(ActionableError.of(
                "Invalid Java toolchain metadata returned by " + provider + ".",
                "Retry `zolt toolchain sync`; if the response remains invalid, report the upstream metadata response.",
                cause));
    }
}
