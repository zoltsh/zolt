package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;

final class PublishDependencyRootCoverageTest {
    private final PublishPomGenerator generator = new PublishPomGenerator();

    @Test
    void rejectsPreV7AndMissingNormalOrPublishOnlyRoots() {
        ProjectConfig empty = config("");
        PublishException old = assertThrows(
                PublishException.class,
                () -> generator.generate(empty, new ZoltLockfile(6, List.of(), List.of())));
        assertTrue(old.getMessage().contains("requires zolt.lock version 7"));

        ProjectConfig normal = config("""
                [dependencies]
                "org.example:missing" = "1.0.0"
                """);
        PublishException missing = assertThrows(
                PublishException.class,
                () -> generator.generate(normal, new ZoltLockfile(7, List.of(), List.of())));
        assertTrue(missing.getMessage().contains("implementation:org.example:missing:jar"));

        ProjectConfig publishOnly = config("""
                [api.dependencies]
                "org.example:publish-helper" = { version = "2.0.0", publishOnly = true }
                """);
        PublishException missingPublishOnly = assertThrows(
                PublishException.class,
                () -> generator.generate(publishOnly, new ZoltLockfile(7, List.of(), List.of())));
        assertTrue(missingPublishOnly.getMessage().contains("api:org.example:publish-helper:jar:publish-only"));
    }

    @Test
    void rejectsAStaleExtraPublishedRoot() {
        LockDependencyRoot stale = new LockDependencyRoot(
                ".",
                new PackageId("org.example", "stale"),
                "9.0.0",
                null,
                DependencyLane.API,
                Optional.empty(),
                false,
                true);

        PublishException exception = assertThrows(
                PublishException.class,
                () -> generator.generate(config(""), lockfile(stale)));

        assertTrue(exception.getMessage().contains("unexpected [`api:org.example:stale:jar:publish-only`]"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void rejectsOptionalAndPublishOnlyFactDrift() {
        ProjectConfig optional = config("""
                [dependencies]
                "org.example:helper" = { version = "1", optional = true, publishOnly = true }
                """);
        LockDependencyRoot missingOptional = new LockDependencyRoot(
                ".", new PackageId("org.example", "helper"), "1", null,
                DependencyLane.IMPLEMENTATION, Optional.empty(), false, true);
        PublishException optionalDrift = assertThrows(
                PublishException.class,
                () -> generator.generate(optional, lockfile(missingOptional)));
        assertTrue(optionalDrift.getMessage().contains(
                "missing [`implementation:org.example:helper:jar:optional:publish-only`]"));
        assertTrue(optionalDrift.getMessage().contains(
                "unexpected [`implementation:org.example:helper:jar:publish-only`]"));

        ProjectConfig normal = config("""
                [api.dependencies]
                "org.example:normal" = "2"
                """);
        LockDependencyRoot forgedPublishOnly = new LockDependencyRoot(
                ".", new PackageId("org.example", "normal"), "2", null,
                DependencyLane.API, Optional.empty(), false, true);
        PublishException publishOnlyDrift = assertThrows(
                PublishException.class,
                () -> generator.generate(normal, lockfile(forgedPublishOnly)));
        assertTrue(publishOnlyDrift.getMessage().contains("missing [`api:org.example:normal:jar`]"));
        assertTrue(publishOnlyDrift.getMessage().contains("unexpected [`api:org.example:normal:jar:publish-only`]"));
    }

    @Test
    void trustsSelectedVersionsForOrdinaryFixedAliasManagedAndWorkspaceRoots() {
        ProjectConfig declarations = config("""
                [versions]
                aliased = "2.0.0"

                [api.dependencies]
                "org.example:fixed" = "1.0.0"
                "org.example:managed" = {}

                [dependencies]
                "org.example:aliased" = { versionRef = "aliased" }
                "com.example:workspace-lib" = { workspace = "libs/workspace-lib" }
                """);
        LockDependencyRoot fixed = root("org.example", "fixed", "1.0.1", DependencyLane.API);
        LockDependencyRoot managed = root("org.example", "managed", "7.0.0", DependencyLane.API);
        LockDependencyRoot aliased = root("org.example", "aliased", "2.0.1", DependencyLane.IMPLEMENTATION);
        LockDependencyRoot workspace = root("com.example", "workspace-lib", "3.0.0", DependencyLane.IMPLEMENTATION);

        String pom = generator.generate(
                declarations,
                lockfile(List.of(fixed, managed, aliased, workspace)));

        assertTrue(pom.contains("<artifactId>fixed</artifactId>\n"
                + "      <version>1.0.1</version>"));
        assertTrue(pom.contains("<artifactId>managed</artifactId>\n"
                + "      <version>7.0.0</version>"));
        assertTrue(pom.contains("<artifactId>aliased</artifactId>\n"
                + "      <version>2.0.1</version>"));
        assertTrue(pom.contains("<artifactId>workspace-lib</artifactId>\n"
                + "      <version>3.0.0</version>"));
    }

    @Test
    void rejectsAStaleFixedPublishOnlyVersion() {
        ProjectConfig fixed = config("""
                [dependencies]
                "org.example:fixed" = { version = "3.0.0", publishOnly = true }
                """);
        LockDependencyRoot stale = new LockDependencyRoot(
                ".", new PackageId("org.example", "fixed"), "2.0.0", null,
                DependencyLane.IMPLEMENTATION, Optional.empty(), false, true);

        PublishException exception = assertThrows(
                PublishException.class,
                () -> generator.generate(fixed, lockfile(stale)));

        assertTrue(exception.getMessage().contains("expected `3.0.0` but locked `2.0.0`"));
    }

    private static ProjectConfig config(String dependencySections) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "app"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                """ + dependencySections);
    }

    private static ZoltLockfile lockfile(LockDependencyRoot root) {
        return lockfile(List.of(root));
    }

    private static ZoltLockfile lockfile(List<LockDependencyRoot> roots) {
        List<LockPackage> packages = roots.stream()
                .filter(root -> !root.publishOnly())
                .map(PublishDependencyRootCoverageTest::selectedPackage)
                .toList();
        return new ZoltLockfile(
                7, Optional.empty(), Optional.empty(), List.of(), packages, List.of(), List.of(), List.of(), roots);
    }

    private static LockDependencyRoot root(
            String group,
            String artifact,
            String version,
            DependencyLane lane) {
        return new LockDependencyRoot(
                ".",
                new PackageId(group, artifact),
                version,
                null,
                lane,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);
    }

    private static LockPackage selectedPackage(LockDependencyRoot root) {
        return new LockPackage(
                root.packageId(),
                root.version(),
                ProjectConfig.MAVEN_CENTRAL,
                root.resolvedScope().orElseThrow(),
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }
}
