package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencyWhyFormatterTest extends DependencyWhyTestSupport {
    private final DependencyWhyFormatter formatter = new DependencyWhyFormatter();

    @Test
    void printsStablePathToTransitivePackage() {
        String output = formatter.format(
                config(),
                lockfile(),
                new PackageId("com.example", "lib"));

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                   \\- com.example:lib:1.0.0
                """, output);
    }

    @Test
    void printsStablePathToDirectPackage() {
        String output = formatter.format(
                config(),
                lockfile(),
                new PackageId("com.example", "app"));

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                """, output);
    }

    @Test
    void showsPoliciesOnPathPackages() {
        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage(
                        "com.example",
                        "app",
                        "1.0.0",
                        true,
                        List.of(),
                        List.of("managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0"))),
                List.of());

        String output = formatter.format(config(), lockfile, new PackageId("com.example", "app"));

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile) (policy: managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0)
                """, output);
    }

    @Test
    void showsStrictPoliciesOnPathPackages() {
        String output = formatter.format(config(), strictPolicyLockfile(), new PackageId("com.example", "lib"));

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                   \\- com.example:lib:2.0.0 (policy: strict-version: com.example:lib requested 1.0.0 -> 2.0.0 (baseline))
                """, output);
    }

    @Test
    void showsConflictDetailsForTargetPackage() {
        String output = formatter.format(config(), conflictedLockfile(), new PackageId("com.example", "lib"));

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                   \\- com.example:lib:2.0.0 (conflict: selected 2.0.0; requested 1.0.0, 2.0.0; newest version wins)
                """, output);
    }

    @Test
    void missingPackageMessageExplainsNextStep() {
        DependencyWhyException exception = assertThrows(
                DependencyWhyException.class,
                () -> formatter.format(config(), lockfile(), new PackageId("com.example", "missing")));

        assertEquals(
                "Package com.example:missing is not present in zolt.lock. Run `zolt resolve` after adding it or check the package id.",
                exception.getMessage());
    }

    @Test
    void explainsPackageAbsentBecausePolicyExcludedIt() {
        String output = formatter.format(config(), excludedLockfile(), new PackageId("commons-logging", "commons-logging"));

        assertEquals("""
                com.example:demo:0.1.0
                \\- commons-logging:commons-logging (excluded by dependency policy)
                   \\- global-exclusion requested 1.2 from com.example:app:1.0.0: [dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)
                """, output);
    }
}
