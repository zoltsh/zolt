package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.PackageId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DependencyTreeJsonTest extends DependencyTreeTestSupport {
    private final DependencyJsonFormatter jsonFormatter = new DependencyJsonFormatter();

    @Test
    void formatsStableJsonWithPolicyEffects() {
        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage(
                        "com.example",
                        "app",
                        "1.0.0",
                        true,
                        List.of("com.example:lib:1.0.0"),
                        List.of("managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0")),
                        lockPackage("com.example", "lib", "1.0.0", false, List.of())),
                List.of(new LockConflict(
                        new PackageId("com.example", "lib"),
                        "1.0.0",
                        List.of("0.9.0", "1.0.0"),
                        ConflictSelectionReason.DIRECT_DEPENDENCY)),
                List.of(new LockPolicyEffect(
                        "global-exclusion",
                        new PackageId("commons-logging", "commons-logging"),
                        Optional.of("1.2"),
                        Optional.of("com.example:app:1.0.0"),
                        "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)")));

        String output = jsonFormatter.tree(config(), lockfile);

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "command": "tree",
                  "project": {
                    "group": "com.example",
                    "name": "demo",
                    "version": "0.1.0",
                    "coordinate": "com.example:demo:0.1.0"
                  },
                  "packages": [
                    {
                      "id": "com.example:app",
                      "version": "1.0.0",
                      "coordinate": "com.example:app:1.0.0",
                      "scope": "compile",
                      "direct": true,
                      "dependencies": ["com.example:lib:1.0.0"],
                      "policies": ["managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0"]
                    },
                    {
                      "id": "com.example:lib",
                      "version": "1.0.0",
                      "coordinate": "com.example:lib:1.0.0",
                      "scope": "compile",
                      "direct": false,
                      "dependencies": [],
                      "policies": []
                    }
                  ],
                  "roots": ["com.example:app:1.0.0"],
                  "conflicts": [
                    {
                      "id": "com.example:lib",
                      "selected": "1.0.0",
                      "requested": ["0.9.0", "1.0.0"],
                      "reason": "direct dependency wins"
                    }
                  ],
                  "policyEffects": [
                    {
                      "kind": "global-exclusion",
                      "id": "commons-logging:commons-logging",
                      "requested": "1.2",
                      "source": "com.example:app:1.0.0",
                      "policy": "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)"
                    }
                  ]
                }
                """, output);
    }

    @Test
    void emitsQualifiedPackageAndConflictVariants() {
        ZoltLockfile lockfile = lockfile(
                List.of(classified("io.netty", "netty", "4.1.100.Final", "linux-x86_64")),
                List.of(new LockConflict(
                        new PackageId("io.netty", "netty"),
                        "4.1.100.Final",
                        List.of("4.1.90.Final", "4.1.100.Final"),
                        ConflictSelectionReason.NEWEST_VERSION,
                        Optional.empty(),
                        Optional.of(new sh.zolt.lockfile.LockArtifactVariant(
                                "jar", Optional.of("linux-x86_64"))),
                        List.of("modules/core", "apps/api"))));

        String output = jsonFormatter.tree(config(), lockfile);

        org.junit.jupiter.api.Assertions.assertTrue(
                output.contains("\"coordinate\": \"io.netty:netty:4.1.100.Final:jar|linux-x86_64\""));
        assertEquals(
                2,
                output.split(
                                java.util.regex.Pattern.quote("\"variant\": \"jar|linux-x86_64\""),
                                -1)
                        .length
                        - 1);
        org.junit.jupiter.api.Assertions.assertTrue(
                output.contains("\"members\": [\"apps/api\", \"modules/core\"]"));
    }
}
