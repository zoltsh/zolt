package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PackageEvidenceJsonReader {
    private final String json;
    private final Path manifestPath;

    PackageEvidenceJsonReader(String json, Path manifestPath) {
        this.json = json;
        this.manifestPath = manifestPath;
    }

    List<PackageEvidenceJsonReader> objectArray(String field) {
        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) {
            return List.of();
        }
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) {
            throw malformed(field);
        }
        int arrayStart = nextNonWhitespace(colon + 1);
        if (arrayStart < 0 || json.charAt(arrayStart) != '[') {
            throw malformed(field);
        }
        int arrayEnd = matching(arrayStart, '[', ']', field);
        List<PackageEvidenceJsonReader> objects = new ArrayList<>();
        int index = arrayStart + 1;
        while (index < arrayEnd) {
            index = nextNonWhitespace(index);
            if (index < 0 || index >= arrayEnd) {
                break;
            }
            char character = json.charAt(index);
            if (character == ',') {
                index++;
                continue;
            }
            if (character != '{') {
                throw malformed(field);
            }
            int objectEnd = matching(index, '{', '}', field);
            objects.add(new PackageEvidenceJsonReader(json.substring(index, objectEnd + 1), manifestPath));
            index = objectEnd + 1;
        }
        return List.copyOf(objects);
    }

    int requiredInt(String field) {
        int fieldStart = requiredFieldStart(field);
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) {
            throw malformed(field);
        }
        int valueStart = nextNonWhitespace(colon + 1);
        if (valueStart < 0) {
            throw malformed(field);
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            throw malformed(field);
        }
        return Integer.parseInt(json.substring(valueStart, valueEnd));
    }

    boolean requiredBoolean(String field) {
        int fieldStart = requiredFieldStart(field);
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) {
            throw malformed(field);
        }
        int valueStart = nextNonWhitespace(colon + 1);
        if (valueStart >= 0
                && json.startsWith("true", valueStart)) {
            return true;
        }
        if (valueStart >= 0
                && json.startsWith("false", valueStart)) {
            return false;
        }
        throw malformed(field);
    }

    String requiredString(String field) {
        int fieldStart = requiredFieldStart(field);
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) {
            throw malformed(field);
        }
        int quoteStart = nextNonWhitespace(colon + 1);
        if (quoteStart < 0 || json.charAt(quoteStart) != '"') {
            throw malformed(field);
        }
        return stringValue(quoteStart).value();
    }

    Optional<String> optionalString(String field) {
        if (json.indexOf("\"" + field + "\"") < 0) {
            return Optional.empty();
        }
        return Optional.of(requiredString(field));
    }

    Optional<String> nullableString(String field) {
        int fieldStart = requiredFieldStart(field);
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) {
            throw malformed(field);
        }
        int valueStart = nextNonWhitespace(colon + 1);
        if (valueStart < 0) {
            throw malformed(field);
        }
        if (json.startsWith("null", valueStart)) {
            return Optional.empty();
        }
        if (json.charAt(valueStart) != '"') {
            throw malformed(field);
        }
        return Optional.of(stringValue(valueStart).value());
    }

    List<String> stringArray(String field) {
        int fieldStart = requiredFieldStart(field);
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) {
            throw malformed(field);
        }
        int arrayStart = nextNonWhitespace(colon + 1);
        if (arrayStart < 0 || json.charAt(arrayStart) != '[') {
            throw malformed(field);
        }
        int arrayEnd = matching(arrayStart, '[', ']', field);
        List<String> values = new ArrayList<>();
        int index = arrayStart + 1;
        while (index < arrayEnd) {
            index = nextNonWhitespace(index);
            if (index < 0 || index >= arrayEnd) {
                break;
            }
            char character = json.charAt(index);
            if (character == ',') {
                index++;
                continue;
            }
            if (character != '"') {
                throw malformed(field);
            }
            StringValue value = stringValue(index);
            values.add(value.value());
            index = value.endIndex() + 1;
        }
        return List.copyOf(values);
    }

    private int requiredFieldStart(String field) {
        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) {
            throw malformed(field);
        }
        return fieldStart;
    }

    private int matching(
            int start,
            char open,
            char close,
            String field) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int index = start; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (character == '\\') {
                escaping = inString;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (character == open) {
                depth++;
            } else if (character == close) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw malformed(field);
    }

    private StringValue stringValue(int quoteStart) {
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int index = quoteStart + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaping) {
                value.append(unescaped(character, index));
                escaping = false;
                continue;
            }
            if (character == '\\') {
                escaping = true;
                continue;
            }
            if (character == '"') {
                return new StringValue(value.toString(), index);
            }
            value.append(character);
        }
        throw malformed("string");
    }

    private int nextNonWhitespace(int start) {
        for (int index = start; index < json.length(); index++) {
            if (!Character.isWhitespace(json.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private char unescaped(char character, int index) {
        return switch (character) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> throw new PackageException(
                    "Could not parse package evidence manifest string escape near character "
                            + index
                            + ". Regenerate package evidence with `zolt package`.");
        };
    }

    private PackageException malformed(String field) {
        return new PackageException(
                "Package evidence manifest "
                        + manifestPath
                        + " is missing string field `"
                        + field
                        + "`. Regenerate package evidence with `zolt package`.");
    }

    private record StringValue(String value, int endIndex) {}
}
