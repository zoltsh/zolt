package sh.zolt.toolchain.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.platform.HostPlatform;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceToolchainLockIndexTest {
    private static final HostPlatform PLATFORM =
            HostPlatform.parse("linux-x64");

    @Test
    void findsPreferManagedLockAfterPolicyChangesToRequireManaged() {
        assertPolicyTransition(
                ToolchainPolicy.PREFER_MANAGED,
                ToolchainPolicy.REQUIRE_MANAGED);
    }

    @Test
    void findsRequireManagedLockAfterPolicyChangesToPreferManaged() {
        assertPolicyTransition(
                ToolchainPolicy.REQUIRE_MANAGED,
                ToolchainPolicy.PREFER_MANAGED);
    }

    private static void assertPolicyTransition(
            ToolchainPolicy lockedPolicy,
            ToolchainPolicy currentPolicy) {
        JavaToolchainRequest lockedRequest = request(lockedPolicy);
        LockedJavaToolchain locked = new LockedJavaToolchain(
                "java-temurin-21",
                lockedRequest,
                PLATFORM,
                "21",
                JavaDistribution.TEMURIN,
                "builtin:java-temurin-21",
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(false));
        WorkspaceToolchainLockIndex index =
                new WorkspaceToolchainLockIndex(java.util.List.of(locked));

        assertEquals(
                locked,
                index.find(request(currentPolicy), PLATFORM).orElseThrow());
    }

    private static JavaToolchainRequest request(
            ToolchainPolicy policy) {
        return new JavaToolchainRequest(
                "21",
                JavaDistribution.TEMURIN,
                Set.<JavaFeature>of(),
                policy);
    }
}
