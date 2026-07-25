package sh.zolt.lockfile.toml;

import sh.zolt.dependency.PackageId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

final class LockfileTomlValues {
    private LockfileTomlValues() {
    }

    static int requireInt(TomlTable table, String key) {
        Long value = table.getLong(key);
        if (value == null) {
            throw new LockfileReadException("Missing required integer field `" + key + "` in zolt.lock.");
        }
        return Math.toIntExact(value);
    }

    static String requireString(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null || value.isBlank()) {
            throw new LockfileReadException("Missing required string field `" + key + "` in zolt.lock.");
        }
        return value;
    }

    static boolean requireBoolean(TomlTable table, String key) {
        Boolean value = table.getBoolean(key);
        if (value == null) {
            throw new LockfileReadException("Missing required boolean field `" + key + "` in zolt.lock.");
        }
        return value;
    }

    static Optional<String> optionalString(TomlTable table, String key) {
        String value = table.getString(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    static boolean optionalBoolean(TomlTable table, String key) {
        Boolean value = table.getBoolean(key);
        return value != null && value;
    }

    static List<String> stringArray(TomlTable table, String key) {
        TomlArray array = table.getArray(key);
        if (array == null) {
            throw new LockfileReadException("Missing required string array field `" + key + "` in zolt.lock.");
        }

        return strings(array, key);
    }

    static List<String> optionalStringArray(TomlTable table, String key) {
        TomlArray array = table.getArray(key);
        if (array == null) {
            return List.of();
        }

        return strings(array, key);
    }

    static PackageId packageId(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new LockfileReadException(
                    "Invalid package id `" + value + "` in zolt.lock. Expected `group:artifact`.");
        }
        return new PackageId(parts[0], parts[1]);
    }

    private static List<String> strings(TomlArray array, String key) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String value = array.getString(index);
            if (value == null || value.isBlank()) {
                throw new LockfileReadException(
                        "Invalid string value at `" + key + "[" + index + "]` in zolt.lock.");
            }
            values.add(value);
        }
        return values;
    }
}
