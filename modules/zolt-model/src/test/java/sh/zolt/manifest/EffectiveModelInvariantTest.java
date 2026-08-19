package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class EffectiveModelInvariantTest {
    private static final ManifestSource PROJECT =
            new ManifestSource("modules/core/zolt.toml", "project.name");
    private static final ManifestSource ROOT =
            new ManifestSource("zolt.toml", "workspace.project.group");

    @Test
    void enforcesProjectIdentityProvenanceDomains() {
        EffectiveValue<ProjectName> name =
                EffectiveValue.authored(new ProjectName("core"), PROJECT);
        EffectiveValue<ProjectVersion> version = EffectiveValue.authored(
                new ProjectVersion("1.0.0"), source("project.version"));
        EffectiveValue<ProjectGroup> group =
                EffectiveValue.inherited(new ProjectGroup("com.example"), ROOT);
        EffectiveValue<JavaFeatureRelease> javaRelease = EffectiveValue.authored(
                new JavaFeatureRelease(21), source("project.java"));
        EffectiveValue<ProjectLicense> license = EffectiveValue.authored(
                new ProjectLicense.Identifier("Apache-2.0"), source("project.license"));

        assertDoesNotThrow(() -> new EffectiveProjectIdentity(
                name, version, group, Optional.of(javaRelease), Optional.of(license)));
        List<Executable> invalid = List.of(
                () -> new EffectiveProjectIdentity(
                        EffectiveValue.inherited(new ProjectName("core"), ROOT),
                        version,
                        group,
                        Optional.of(javaRelease),
                        Optional.of(license)),
                () -> new EffectiveProjectIdentity(
                        name,
                        EffectiveValue.builtIn(new ProjectVersion("1.0.0")),
                        group,
                        Optional.of(javaRelease),
                        Optional.of(license)),
                () -> new EffectiveProjectIdentity(
                        name,
                        version,
                        EffectiveValue.builtIn(new ProjectGroup("com.example")),
                        Optional.of(javaRelease),
                        Optional.of(license)),
                () -> new EffectiveProjectIdentity(
                        name,
                        version,
                        group,
                        Optional.of(EffectiveValue.builtIn(new JavaFeatureRelease(21))),
                        Optional.of(license)),
                () -> new EffectiveProjectIdentity(
                        name,
                        version,
                        group,
                        Optional.of(javaRelease),
                        Optional.of(EffectiveValue.builtIn(
                                new ProjectLicense.Identifier("Apache-2.0")))));

        invalid.forEach(candidate -> assertThrows(IllegalArgumentException.class, candidate));
    }

    @Test
    void rejectsBuiltInOriginsForAuthoredSharedDomains() {
        LocalId entry = new LocalId("entry");
        DependencyRepository repository = DependencyRepository.unauthenticated(
                new RepositoryUrl("https://repo.example.test/maven"));
        AuthoredTask task = new AuthoredTask(
                Optional.empty(), List.of("audit.sh"), Optional.empty(), Map.of());
        AuthoredAlias alias = new AuthoredAlias(List.of("build"));

        List<Executable> invalid = List.of(
                () -> new WorkspaceContext(
                        EffectiveValue.builtIn(new LocalId("workspace")),
                        new WorkspaceMemberPath("modules/core")),
                () -> new EffectiveDependencyRepositories(
                        builtInCentral(),
                        Map.of(entry, EffectiveValue.builtIn(repository)),
                        EffectiveValue.builtIn(List.of(entry, new LocalId("central")))),
                () -> new EffectiveCoverage(
                        Optional.of(EffectiveValue.builtIn(new CoveragePercentage(80))),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                () -> new EffectiveCommands(
                        Map.of(entry, EffectiveValue.builtIn(task)), Map.of()),
                () -> new EffectiveCommands(
                        Map.of(), Map.of(entry, EffectiveValue.builtIn(alias))),
                () -> new EffectiveToolchains(
                        Optional.of(EffectiveValue.builtIn(new ZoltVersionPin("0.1.0"))),
                        Optional.empty(),
                        Optional.empty()),
                () -> shared(
                        Map.of(entry, EffectiveValue.builtIn(new VersionAliasValue("1.0.0"))),
                        Map.of(),
                        Map.of()),
                () -> shared(
                        Map.of(),
                        Map.of(entry, EffectiveValue.builtIn(new RepositoryCredential.BearerToken(
                                new EnvironmentVariableName("TOKEN")))),
                        Map.of()),
                () -> shared(
                        Map.of(),
                        Map.of(),
                        Map.of(
                                new DependencyCoordinate("com.example:platform"),
                                EffectiveValue.builtIn(new PlatformSelector.FixedVersion("1.0.0")))));

        invalid.forEach(candidate -> assertThrows(IllegalArgumentException.class, candidate));
    }

    @Test
    void acceptsInheritedOriginsForEverySharedAuthoredDomain() {
        LocalId entry = new LocalId("entry");
        ManifestSource source = new ManifestSource("zolt.toml", "versions.entry");
        EffectiveDependencyRepositories repositories = new EffectiveDependencyRepositories(
                builtInCentral(),
                Map.of(
                        entry,
                        EffectiveValue.inherited(
                                DependencyRepository.unauthenticated(
                                        new RepositoryUrl("https://repo.example.test/maven")),
                                new ManifestSource("zolt.toml", "repositories.entry"))),
                EffectiveValue.builtIn(List.of(entry, new LocalId("central"))));
        EffectiveCommands commands = new EffectiveCommands(
                Map.of(
                        entry,
                        EffectiveValue.inherited(
                                new AuthoredTask(
                                        Optional.empty(), List.of("audit.sh"), Optional.empty(), Map.of()),
                                new ManifestSource("zolt.toml", "tasks.entry"))),
                Map.of());

        assertDoesNotThrow(() -> new EffectiveSharedConfiguration(
                Map.of(entry, EffectiveValue.inherited(new VersionAliasValue("1.0.0"), source)),
                repositories,
                Map.of(
                        entry,
                        EffectiveValue.inherited(
                                new RepositoryCredential.BearerToken(
                                        new EnvironmentVariableName("TOKEN")),
                                new ManifestSource("zolt.toml", "credentials.entry"))),
                Map.of(
                        new DependencyCoordinate("com.example:platform"),
                        EffectiveValue.inherited(
                                new PlatformSelector.FixedVersion("1.0.0"),
                                new ManifestSource("zolt.toml", "platforms.com.example:platform"))),
                EffectiveToolchains.withoutJava(Optional.of(EffectiveValue.inherited(
                        new ZoltVersionPin("0.1.0"),
                        new ManifestSource("zolt.toml", "toolchain.zolt.version")))),
                new EffectiveCoverage(
                        Optional.of(EffectiveValue.inherited(
                                new CoveragePercentage(80),
                                new ManifestSource("zolt.toml", "coverage.line"))),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                commands));
        assertDoesNotThrow(() -> new WorkspaceContext(
                EffectiveValue.inherited(
                        new LocalId("workspace"),
                        new ManifestSource("zolt.toml", "workspace.name")),
                new WorkspaceMemberPath("modules/core")));
    }

    @Test
    void restrictsBuiltInCentralAndLookupOrderToTheirLanguageDefaults() {
        LocalId alpha = new LocalId("alpha");
        LocalId zeta = new LocalId("zeta");
        Map<LocalId, EffectiveValue<DependencyRepository>> named = Map.of(
                zeta, authoredRepository("zeta"),
                alpha, authoredRepository("alpha"));
        List<LocalId> defaultOrder = List.of(alpha, zeta, new LocalId("central"));

        EffectiveDependencyRepositories defaults = new EffectiveDependencyRepositories(
                builtInCentral(), named, EffectiveValue.builtIn(defaultOrder));
        assertEquals(defaultOrder, defaults.lookupOrder().value());

        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveDependencyRepositories(
                        EffectiveValue.builtIn(EffectiveCentralRepository.disabled()),
                        Map.of(),
                        EffectiveValue.builtIn(List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveDependencyRepositories(
                        EffectiveValue.builtIn(EffectiveCentralRepository.enabled(
                                DependencyRepository.unauthenticated(
                                        new RepositoryUrl("https://mirror.example.test/maven")))),
                        Map.of(),
                        EffectiveValue.builtIn(List.of(new LocalId("central")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveDependencyRepositories(
                        builtInCentral(),
                        named,
                        EffectiveValue.builtIn(
                                List.of(zeta, alpha, new LocalId("central")))));
    }

    private static EffectiveSharedConfiguration shared(
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            Map<LocalId, EffectiveValue<RepositoryCredential>> credentials,
            Map<DependencyCoordinate, EffectiveValue<PlatformSelector>> platforms) {
        return new EffectiveSharedConfiguration(
                versions,
                defaultRepositories(),
                credentials,
                platforms,
                EffectiveToolchains.withoutJava(Optional.empty()),
                EffectiveCoverage.empty(),
                EffectiveCommands.empty());
    }

    private static EffectiveValue<EffectiveCentralRepository> builtInCentral() {
        return EffectiveValue.builtIn(EffectiveCentralRepository.enabled(
                DependencyRepository.unauthenticated(
                        AuthoredDependencyRepositories.MAVEN_CENTRAL_URL)));
    }

    private static EffectiveValue<DependencyRepository> authoredRepository(String id) {
        return EffectiveValue.authored(
                DependencyRepository.unauthenticated(
                        new RepositoryUrl("https://" + id + ".example.test/maven")),
                source("repositories." + id));
    }

    private static EffectiveDependencyRepositories defaultRepositories() {
        return new EffectiveDependencyRepositories(
                builtInCentral(),
                Map.of(),
                EffectiveValue.builtIn(List.of(new LocalId("central"))));
    }

    private static ManifestSource source(String field) {
        return new ManifestSource("modules/core/zolt.toml", field);
    }
}
