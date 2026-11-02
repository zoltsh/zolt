package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.resolve.request.DependencyRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Stable lockfile identity for an independently resolved compiler closure. */
final class CompilerConflictResolutionKey {
    private CompilerConflictResolutionKey() {
    }

    static String of(CompilerToolResolution tool) {
        List<String> roots = tool.directRequests().stream()
                .map(CompilerConflictResolutionKey::rootIdentity)
                .distinct()
                .sorted()
                .toList();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "Compiler conflict attribution requires at least one direct compiler request.");
        }
        return LockConflict.compilerToolGroup(tool.scope(), String.join("~", roots));
    }

    private static String rootIdentity(DependencyRequest request) {
        return encode(request.packageId().toString())
                + "." + encode(request.requestedVersion())
                + "." + encode(request.artifactVariant().key());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
