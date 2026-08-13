package sh.zolt.lockfile.toml;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlTable;

final class LockfilePackageCodec {
    List<LockPackage> packages(TomlArray packageArray) {
        if (packageArray == null) {
            return List.of();
        }

        List<LockPackage> packages = new ArrayList<>();
        for (int index = 0; index < packageArray.size(); index++) {
            TomlTable table = packageTable(packageArray, index);
            if (table == null) {
                throw new LockfileReadException("Invalid package entry at index " + index + " in zolt.lock.");
            }
            packages.add(lockPackage(table));
        }
        return packages;
    }

    private static TomlTable packageTable(TomlArray packageArray, int index) {
        try {
            return packageArray.getTable(index);
        } catch (TomlInvalidTypeException exception) {
            throw new LockfileReadException(
                    "Invalid package entry at index " + index + " in zolt.lock.",
                    exception);
        }
    }

    private static LockPackage lockPackage(TomlTable table) {
        PackageId packageId = LockfileTomlValues.packageId(LockfileTomlValues.requireString(table, "id"));
        try {
            return new LockPackage(
                    packageId,
                    LockfileTomlValues.requireString(table, "version"),
                    LockfileTomlValues.requireString(table, "source"),
                    scope(LockfileTomlValues.requireString(table, "scope"), packageId),
                    LockfileTomlValues.requireBoolean(table, "direct"),
                    LockfileTomlValues.optionalString(table, "jar"),
                    LockfileTomlValues.optionalString(table, "pom"),
                    LockfileTomlValues.optionalString(table, "jarSha256"),
                    LockfileTomlValues.optionalString(table, "pomSha256"),
                    LockfileTomlValues.optionalString(table, "artifact"),
                    LockfileTomlValues.optionalString(table, "artifactType"),
                    LockfileTomlValues.optionalString(table, "artifactSha256"),
                    LockfileTomlValues.optionalString(table, "workspace"),
                    LockfileTomlValues.optionalString(table, "workspaceOutput"),
                    LockfileTomlValues.stringArray(table, "dependencies"),
                    LockfileTomlValues.optionalStringArray(table, "members"),
                    LockfileTomlValues.optionalStringArray(table, "exportedBy"),
                    LockfileTomlValues.optionalStringArray(table, "policies"),
                    LockfileTomlValues.optionalStringArray(table, "toolGroups"));
        } catch (IllegalArgumentException exception) {
            throw new LockfileReadException(
                    "Invalid cache path for " + packageId + " in zolt.lock: " + exception.getMessage(),
                    exception);
        }
    }

    private static DependencyScope scope(String value, PackageId packageId) {
        for (DependencyScope scope : DependencyScope.values()) {
            if (scope.lockfileName().equals(value)) {
                return scope;
            }
        }
        try {
            return DependencyScope.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new LockfileReadException(
                    "Invalid scope `" + value + "` for " + packageId + " in zolt.lock.",
                    exception);
        }
    }
}
