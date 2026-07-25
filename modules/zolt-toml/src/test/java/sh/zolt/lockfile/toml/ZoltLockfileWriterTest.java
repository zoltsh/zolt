package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ZoltLockfileWriterTest {
    private final ZoltLockfileWriter writer = new ZoltLockfileWriter();

    @Test
    void writesGoldenLockfile() throws IOException {
        String expected = new String(
                ZoltLockfileWriterTest.class.getResourceAsStream("/golden/zolt-lock-writer.golden").readAllBytes(),
                StandardCharsets.UTF_8);

        assertEquals(expected.stripTrailing(), writer.write(unsortedLockfile()).stripTrailing());
    }

    @Test
    void sameInputProducesStableOutput() {
        ZoltLockfile lockfile = unsortedLockfile();

        assertEquals(writer.write(lockfile), writer.write(lockfile));
    }

    @Test
    void writesAliasFingerprintWhenPresent() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.of("sha256:alias-inputs"),
                List.of(),
                List.of(),
                List.of());

        assertEquals("""
                version = 4
                aliasFingerprint = "sha256:alias-inputs"

                """, writer.write(lockfile));
    }

    @Test
    void writesProjectResolutionFingerprintWhenPresent() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:project-inputs"),
                List.of("repositories=sha256:repo-inputs", "dependencies.compile=sha256:compile-inputs"),
                List.of(),
                List.of(),
                List.of());

        assertEquals("""
                version = 4
                projectResolutionFingerprint = "sha256:project-inputs"
                projectResolutionInputFingerprints = ["dependencies.compile=sha256:compile-inputs", "repositories=sha256:repo-inputs"]

                """, writer.write(lockfile));
    }

    @Test
    void packagesAreSortedDeterministically() {
        String output = writer.write(unsortedLockfile());

        assertTrue(output.indexOf("com.google.guava:failureaccess") < output.indexOf("com.google.guava:guava"));
        assertTrue(output.indexOf("com.google.guava:guava") < output.indexOf("org.slf4j:slf4j-api"));
    }

    @Test
    void conflictsAreSortedDeterministically() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(
                        conflict("org.slf4j", "slf4j-api"),
                        conflict("com.google.guava", "guava")));

        String output = writer.write(lockfile);

        assertTrue(output.indexOf("com.google.guava:guava") < output.indexOf("org.slf4j:slf4j-api"));
    }

    @Test
    void writesAndRoundTripsToolAttributedConflictsDeterministically() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(
                        new LockConflict(
                                new PackageId("com.example", "shared"),
                                "2.0.0",
                                List.of("1.0.0", "2.0.0"),
                                ConflictSelectionReason.NEWEST_VERSION,
                                Optional.of("beta")),
                        new LockConflict(
                                new PackageId("com.example", "shared"),
                                "3.0.0",
                                List.of("1.0.0", "3.0.0"),
                                ConflictSelectionReason.NEWEST_VERSION,
                                Optional.of("alpha")),
                        new LockConflict(
                                new PackageId("com.example", "shared"),
                                "1.5.0",
                                List.of("1.0.0", "1.5.0"),
                                ConflictSelectionReason.DIRECT_DEPENDENCY)));

        String output = writer.write(lockfile);

        // The same GA mediates in the main graph and in two exec tools; each stays a distinct entry.
        assertTrue(output.contains("tool = \"alpha\""));
        assertTrue(output.contains("tool = \"beta\""));
        // Deterministic order for a shared packageId: main (no tool) first, then tools alphabetically.
        assertTrue(output.indexOf("selected = \"1.5.0\"") < output.indexOf("tool = \"alpha\""));
        assertTrue(output.indexOf("tool = \"alpha\"") < output.indexOf("tool = \"beta\""));

        ZoltLockfile parsed = new ZoltLockfileReader().read(output);
        assertEquals(3, parsed.conflicts().size());
        assertTrue(parsed.conflicts().stream().anyMatch(conflict ->
                conflict.toolGroup().equals(Optional.of("alpha")) && conflict.selectedVersion().equals("3.0.0")));
        assertTrue(parsed.conflicts().stream().anyMatch(conflict ->
                conflict.toolGroup().isEmpty() && conflict.selectedVersion().equals("1.5.0")));
    }

    @Test
    void writesAndRoundTripsVariantQualifiedConflictsOnlyWhenNonDefault() {
        LockConflict plain = new LockConflict(
                new PackageId("io.netty", "netty"),
                "4.1.100.Final",
                List.of("4.1.90.Final", "4.1.100.Final"),
                ConflictSelectionReason.NEWEST_VERSION,
                Optional.empty(),
                Optional.of(new LockArtifactVariant("jar", Optional.empty())));
        LockConflict classified = new LockConflict(
                new PackageId("io.netty", "netty"),
                "4.1.100.Final",
                List.of("4.1.90.Final", "4.1.100.Final"),
                ConflictSelectionReason.NEWEST_VERSION,
                Optional.empty(),
                Optional.of(new LockArtifactVariant("jar", Optional.of("linux-x86_64"))));
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION, List.of(), List.of(plain, classified));

        String output = writer.write(lockfile);

        // The default variant emits no qualifier; only the classified one carries `variant`.
        assertEquals(1, output.split("variant = ", -1).length - 1);
        assertTrue(output.contains("variant = \"jar|linux-x86_64\""));

        ZoltLockfile parsed = new ZoltLockfileReader().read(output);
        assertTrue(parsed.conflicts().stream().anyMatch(conflict ->
                conflict.variant().equals(Optional.of(new LockArtifactVariant("jar", Optional.of("linux-x86_64"))))));
        assertTrue(parsed.conflicts().stream().anyMatch(conflict -> conflict.variant().isEmpty()));
    }

    @Test
    void writesAndRoundTripsMemberQualifiedGraphFactsDeterministically() {
        LockMemberGraph worker = new LockMemberGraph(
                "apps/worker",
                new PackageId("com.example", "root"),
                "1.0.0",
                new LockArtifactVariant("jar", Optional.of("tests")),
                DependencyScope.TEST,
                List.of("com.example:z:1.0.0:jar:test", "com.example:a:1.0.0:jar:test"),
                List.of("managed-z", "managed-a"));
        LockMemberGraph api = new LockMemberGraph(
                "apps/api",
                new PackageId("com.example", "root"),
                "1.0.0",
                LockArtifactVariant.defaultVariant(),
                DependencyScope.COMPILE,
                List.of(),
                List.of());
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(worker, api));

        String output = writer.write(lockfile);
        ZoltLockfile parsed = new ZoltLockfileReader().read(output);

        assertTrue(output.indexOf("member = \"apps/api\"") < output.indexOf("member = \"apps/worker\""));
        assertEquals(1, countOccurrences(output, "variant = \"jar|tests\""));
        assertEquals(List.of(api, new LockMemberGraph(
                worker.member(),
                worker.packageId(),
                worker.version(),
                worker.variant(),
                worker.scope(),
                List.of("com.example:a:1.0.0:jar:test", "com.example:z:1.0.0:jar:test"),
                List.of("managed-a", "managed-z"))), parsed.memberGraphs());
        assertEquals(output, writer.write(parsed));
    }

    @Test
    void writesPolicyEffectsDeterministically() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(),
                List.of(
                        new LockPolicyEffect(
                                "global-exclusion",
                                new PackageId("org.slf4j", "jcl-over-slf4j"),
                                Optional.of("2.0.16"),
                                Optional.of("com.example:app:1.0.0"),
                                "[dependencyPolicy].exclude org.slf4j:jcl-over-slf4j"),
                        new LockPolicyEffect(
                                "global-exclusion",
                                new PackageId("commons-logging", "commons-logging"),
                                Optional.of("1.2"),
                                Optional.of("com.example:app:1.0.0"),
                                "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)")));

        String output = writer.write(lockfile);

        assertTrue(output.indexOf("commons-logging:commons-logging") < output.indexOf("org.slf4j:jcl-over-slf4j"));
        assertTrue(output.contains("""
                [[policy]]
                kind = "global-exclusion"
                id = "commons-logging:commons-logging"
                requested = "1.2"
                source = "com.example:app:1.0.0"
                policy = "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)"
                """));
    }

    @Test
    void escapesTomlControlCharacters() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of(),
                List.of(new LockPolicyEffect(
                        "global-exclusion",
                        new PackageId("com.example", "demo"),
                        Optional.of("1.0.0"),
                        Optional.empty(),
                        "reason:\u0001\u0007\f\u007F")));

        String output = writer.write(lockfile);

        assertTrue(output.contains("policy = \"reason:\\u0001\\u0007\\f\\u007F\""));
        ZoltLockfile parsed = new ZoltLockfileReader().read(output);
        assertEquals("reason:\u0001\u0007\f\u007F", parsed.policyEffects().getFirst().policy());
    }

    @Test
    void disambiguatesSameGavClassifierVariantsDeterministically() {
        LockPackage plain = variantPackage(
                "io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final.jar");
        LockPackage linux = variantPackage(
                "io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar");
        // Reversed input order to prove the sort, not insertion order, decides the layout.
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(linux, plain),
                List.of());

        String output = writer.write(lockfile);

        String plainJar = "jar = \"io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final.jar\"";
        String linuxJar = "jar = \"io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar\"";
        // Both variants of one GAV+scope survive as distinct entries rather than collapsing.
        assertEquals(2, countOccurrences(output, "id = \"io.netty:netty-transport-native-epoll\""));
        assertTrue(output.contains(plainJar));
        assertTrue(output.contains(linuxJar));
        // The classifier tiebreak orders them deterministically: plain ("jar") before classified ("jar|linux-x86_64").
        assertTrue(output.indexOf(plainJar) < output.indexOf(linuxJar));
        // Byte-stable across repeated writes.
        assertEquals(output, writer.write(lockfile));
    }

    private static LockPackage variantPackage(String jarPath) {
        return new LockPackage(
                new PackageId("io.netty", "netty-transport-native-epoll"),
                "4.1.100.Final",
                "maven-central",
                DependencyScope.RUNTIME,
                false,
                Optional.of(jarPath),
                Optional.of("io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final.pom"),
                Optional.of("jar-sha-" + jarPath),
                Optional.of("pom-sha"),
                List.of());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static ZoltLockfile unsortedLockfile() {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:project-inputs"),
                List.of("repositories=sha256:repo-inputs", "dependencies.compile=sha256:compile-inputs"),
                List.of(
                        lockPackage("org.slf4j", "slf4j-api", "2.0.16", DependencyScope.RUNTIME, false, Optional.empty(), Optional.empty(), List.of()),
                        lockPackage("com.google.guava", "guava", "33.4.0-jre", DependencyScope.COMPILE, true, Optional.of("jar-checksum"), Optional.of("pom-checksum"), List.of(
                                "org.slf4j:slf4j-api:2.0.16",
                                "com.google.guava:failureaccess:1.0.2")),
                        lockPackage("com.google.guava", "failureaccess", "1.0.2", DependencyScope.COMPILE, false, Optional.empty(), Optional.empty(), List.of())),
                List.of(new LockConflict(
                        new PackageId("org.slf4j", "slf4j-api"),
                        "2.0.16",
                        List.of("2.0.16", "1.7.36"),
                        ConflictSelectionReason.DIRECT_DEPENDENCY)),
                List.of());
    }

    private static LockPackage lockPackage(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean direct,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            List<String> dependencies) {
        String base = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        return new LockPackage(
                new PackageId(groupId, artifactId),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                jarSha256,
                pomSha256,
                dependencies);
    }

    private static LockConflict conflict(String groupId, String artifactId) {
        return new LockConflict(
                new PackageId(groupId, artifactId),
                "2.0.16",
                List.of("2.0.16", "1.7.36"),
                ConflictSelectionReason.NEWEST_VERSION);
    }
}
