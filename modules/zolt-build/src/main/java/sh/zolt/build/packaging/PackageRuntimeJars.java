package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PackageRuntimeJars {
    private PackageRuntimeJars() {
    }

    public static String nestedJarName(PackageRuntimeJar runtimeJar) {
        return canonicalNestedJarName(runtimeJar);
    }

    public static String canonicalNestedJarName(PackageRuntimeJar runtimeJar) {
        return runtimeJar.artifactIdentity().nestedJarName();
    }

    public static void requireUniqueNestedPaths(
            String location,
            List<PackageRuntimeJar> runtimeJars) {
        Map<String, PackageRuntimeJar> byName = new LinkedHashMap<>();
        for (PackageRuntimeJar runtimeJar : runtimeJars) {
            String name = nestedJarName(runtimeJar);
            PackageRuntimeJar previous = byName.putIfAbsent(name, runtimeJar);
            if (previous != null) {
                throw new PackageException(
                        "Package plan selects duplicate nested path "
                                + location
                                + name
                                + " for "
                                + previous.artifactIdentity().coordinate()
                                + " and "
                                + runtimeJar.artifactIdentity().coordinate()
                                + ".");
            }
        }
    }

    public static byte[] read(PackageRuntimeJar runtimeJar) throws IOException {
        if (!Files.isRegularFile(runtimeJar.jarPath())) {
            throw new PackageException(
                    "Runtime dependency jar for "
                            + runtimeJar.packageId()
                            + " is missing at "
                            + runtimeJar.jarPath()
                            + ". Run `zolt resolve` to refresh the artifact cache and retry.");
        }
        return Files.readAllBytes(runtimeJar.jarPath());
    }
}
