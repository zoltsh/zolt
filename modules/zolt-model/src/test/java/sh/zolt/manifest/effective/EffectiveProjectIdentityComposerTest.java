package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class EffectiveProjectIdentityComposerTest {
    private static final String ROOT = "zolt.toml";
    private static final String MEMBER = "modules/core/zolt.toml";
    private static final ProjectLicense APACHE = new ProjectLicense.Identifier("Apache-2.0");

    private final EffectiveProjectIdentityComposer composer =
            new EffectiveProjectIdentityComposer();

    @Test
    void composesStandaloneIdentityWithAuthoredProvenance() {
        EffectiveProjectIdentity effective = composer.compose(
                project(
                        Optional.of(version("1.2.3")),
                        Optional.of(group("com.member")),
                        Optional.of(java(21)),
                        Optional.of(APACHE)),
                MEMBER,
                Optional.empty(),
                false);

        assertValue(effective.name(), new ProjectName("core"), ValueOrigin.AUTHORED,
                source(MEMBER, "project", "name"));
        assertValue(effective.version(), version("1.2.3"), ValueOrigin.AUTHORED,
                source(MEMBER, "project", "version"));
        assertValue(effective.group(), group("com.member"), ValueOrigin.AUTHORED,
                source(MEMBER, "project", "group"));
        assertValue(effective.javaRelease().orElseThrow(), java(21), ValueOrigin.AUTHORED,
                source(MEMBER, "project", "java"));
        assertValue(effective.license().orElseThrow(), APACHE, ValueOrigin.AUTHORED,
                source(MEMBER, "project", "license"));
    }

    @Test
    void appliesDefaultsOnlyToMissingMemberFields() {
        ProjectLicense localLicense = new ProjectLicense.Identifier("MIT");
        EffectiveProjectIdentity effective = composer.compose(
                project(
                        Optional.of(version("2.0.0")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(localLicense)),
                MEMBER,
                Optional.of(defaults(
                        Optional.of(group("com.workspace")),
                        Optional.of(version("1.0.0")),
                        Optional.of(java(17)),
                        Optional.of(APACHE),
                        ROOT)),
                false);

        assertValue(effective.version(), version("2.0.0"), ValueOrigin.AUTHORED,
                source(MEMBER, "project", "version"));
        assertValue(effective.group(), group("com.workspace"), ValueOrigin.INHERITED,
                source(ROOT, "workspace", "project", "group"));
        assertValue(effective.javaRelease().orElseThrow(), java(17), ValueOrigin.INHERITED,
                source(ROOT, "workspace", "project", "java"));
        assertValue(effective.license().orElseThrow(), localLicense, ValueOrigin.AUTHORED,
                source(MEMBER, "project", "license"));
    }

    @Test
    void rootProjectStillInheritsDefaultsFromItsOwnManifest() {
        EffectiveProjectIdentity effective = composer.compose(
                project(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                ROOT,
                Optional.of(defaults(
                        Optional.of(group("com.workspace")),
                        Optional.of(version("1.0.0")),
                        Optional.of(java(21)),
                        Optional.of(APACHE),
                        ROOT)),
                false);

        assertEquals(ValueOrigin.AUTHORED, effective.name().origin());
        assertValue(effective.version(), version("1.0.0"), ValueOrigin.INHERITED,
                source(ROOT, "workspace", "project", "version"));
        assertValue(effective.group(), group("com.workspace"), ValueOrigin.INHERITED,
                source(ROOT, "workspace", "project", "group"));
        assertValue(effective.javaRelease().orElseThrow(), java(21), ValueOrigin.INHERITED,
                source(ROOT, "workspace", "project", "java"));
        assertValue(effective.license().orElseThrow(), APACHE, ValueOrigin.INHERITED,
                source(ROOT, "workspace", "project", "license"));
    }

    @Test
    void bomIgnoresInheritedJavaAndRejectsAnAuthoredJavaRelease() {
        EffectiveProjectIdentity effective = composer.compose(
                project(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                MEMBER,
                Optional.of(defaults(
                        Optional.of(group("com.workspace")),
                        Optional.of(version("1.0.0")),
                        Optional.of(java(21)),
                        Optional.empty(),
                        ROOT)),
                true);

        assertFalse(effective.javaRelease().isPresent());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> composer.compose(
                        project(
                                Optional.of(version("1.0.0")),
                                Optional.of(group("com.member")),
                                Optional.of(java(21)),
                                Optional.empty()),
                        MEMBER,
                        Optional.empty(),
                        true));
        assertEquals(
                "An effective BOM cannot consume an authored project Java release.",
                failure.getMessage());
    }

    @Test
    void requiresVersionGroupAndJavaOnlyAfterInheritance() {
        assertMissing(
                project(Optional.empty(), Optional.of(group("com.member")),
                        Optional.of(java(21)), Optional.empty()),
                "version");
        assertMissing(
                project(Optional.of(version("1.0.0")), Optional.empty(),
                        Optional.of(java(21)), Optional.empty()),
                "group");
        assertMissing(
                project(Optional.of(version("1.0.0")), Optional.of(group("com.member")),
                        Optional.empty(), Optional.empty()),
                "java");
    }

    @Test
    void rejectsMissingInputsAndInvalidSourcePaths() {
        AuthoredProjectIdentity project = project(
                Optional.of(version("1.0.0")),
                Optional.of(group("com.member")),
                Optional.of(java(21)),
                Optional.empty());

        assertEquals(
                "Authored project identity is required.",
                assertThrows(NullPointerException.class,
                                () -> composer.compose(null, MEMBER, Optional.empty(), false))
                        .getMessage());
        assertEquals(
                "Project manifest path is required.",
                assertThrows(NullPointerException.class,
                                () -> composer.compose(project, null, Optional.empty(), false))
                        .getMessage());
        assertEquals(
                "Workspace project defaults must not be null.",
                assertThrows(NullPointerException.class,
                                () -> composer.compose(project, MEMBER, null, false))
                        .getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> composer.compose(project, "/tmp/zolt.toml", Optional.empty(), false));
    }

    private void assertMissing(AuthoredProjectIdentity project, String field) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> composer.compose(project, MEMBER, Optional.empty(), false));
        assertEquals(
                "Effective project " + field + " requires `project." + field
                        + "` or `workspace.project." + field + "`.",
                failure.getMessage());
    }

    private static AuthoredProjectIdentity project(
            Optional<ProjectVersion> version,
            Optional<ProjectGroup> group,
            Optional<JavaFeatureRelease> javaRelease,
            Optional<ProjectLicense> license) {
        return new AuthoredProjectIdentity(
                new ProjectName("core"), version, group, javaRelease, license);
    }

    private static EffectiveProjectIdentityComposer.WorkspaceDefaults defaults(
            Optional<ProjectGroup> group,
            Optional<ProjectVersion> version,
            Optional<JavaFeatureRelease> javaRelease,
            Optional<ProjectLicense> license,
            String manifestPath) {
        return new EffectiveProjectIdentityComposer.WorkspaceDefaults(
                new AuthoredWorkspaceProjectDefaults(group, version, javaRelease, license),
                manifestPath);
    }

    private static ProjectVersion version(String value) {
        return new ProjectVersion(value);
    }

    private static ProjectGroup group(String value) {
        return new ProjectGroup(value);
    }

    private static JavaFeatureRelease java(int value) {
        return new JavaFeatureRelease(value);
    }

    private static ManifestSource source(String manifestPath, String... fieldPath) {
        return new ManifestSource(manifestPath, List.of(fieldPath));
    }

    private static <T> void assertValue(
            EffectiveValue<T> effective,
            T value,
            ValueOrigin origin,
            ManifestSource source) {
        assertEquals(value, effective.value());
        assertEquals(origin, effective.origin());
        assertEquals(Optional.of(source), effective.source());
    }
}
