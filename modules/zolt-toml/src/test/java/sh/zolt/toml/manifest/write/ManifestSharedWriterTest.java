package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;
import static sh.zolt.toml.manifest.ManifestSharedTestSupport.decodeShared;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredRepositoryControl;
import sh.zolt.manifest.authored.AuthoredVersionAliases;

final class ManifestSharedWriterTest {
    @Test
    void emitsTheCompleteSharedDomainInCanonicalSchemaAndModelOrder() {
        AuthoredVersionAliases versions = new AuthoredVersionAliases(Map.of(
                id("release"), new VersionAliasValue("4.0.6"),
                id("alpha"), new VersionAliasValue("1.2.3")));
        AuthoredDependencyRepositories repositories = new AuthoredDependencyRepositories(
                Optional.of(new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Replacement(
                                url("https://mirror.example.com/maven"),
                                Optional.of(id("company")))),
                        Optional.of(List.of(id("zeta"), id("central"), id("alpha"))))),
                Map.of(
                        id("zeta"),
                        new DependencyRepository(
                                url("https://repo.example.com/zeta"),
                                Optional.of(id("company"))),
                        id("alpha"),
                        DependencyRepository.unauthenticated(
                                url("https://repo.example.com/alpha"))));
        AuthoredCredentials credentials = new AuthoredCredentials(Map.of(
                id("enterprise"),
                new RepositoryCredential.Basic(
                        environment("MAVEN_USERNAME"), environment("MAVEN_PASSWORD")),
                id("company"),
                new RepositoryCredential.BearerToken(environment("MAVEN_TOKEN"))));
        AuthoredPlatforms platforms = new AuthoredPlatforms(Map.of(
                coordinate("org.example:zeta"),
                new PlatformSelector.VersionReference(id("release")),
                coordinate("org.example:alpha"),
                new PlatformSelector.FixedVersion("2.0.0")));

        String output = write(
                Optional.of(versions),
                Optional.of(repositories),
                Optional.of(credentials),
                Optional.of(platforms));

        assertEquals(
                """
                [versions]
                alpha = "1.2.3"
                release = "4.0.6"

                [repositories]
                central = { url = "https://mirror.example.com/maven", credentials = "company" }
                order = ["zeta", "central", "alpha"]

                [repositories.alpha]
                url = "https://repo.example.com/alpha"

                [repositories.zeta]
                url = "https://repo.example.com/zeta"
                credentials = "company"

                [credentials.company]
                tokenEnv = "MAVEN_TOKEN"

                [credentials.enterprise]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [platforms]
                "org.example:alpha" = "2.0.0"
                "org.example:zeta" = { versionRef = "release" }
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertFalse(output.contains("{ }"));

        AuthoredManifest decoded = decodeAuthoredManifest(
                "[project]\nname = \"round-trip\"\n\n" + output);
        assertEquals(Optional.of(versions), decoded.versions());
        assertEquals(Optional.of(repositories), decoded.repositories());
        assertEquals(Optional.of(credentials), decoded.credentials());
        assertEquals(Optional.of(platforms), decoded.platforms());
    }

    @Test
    void omitsAbsentDefaultAndExplicitlyEmptyCanonicalDomains() {
        assertEquals(
                "",
                write(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertEquals(
                "",
                write(
                        Optional.of(AuthoredVersionAliases.empty()),
                        Optional.of(AuthoredDependencyRepositories.defaults()),
                        Optional.of(AuthoredCredentials.empty()),
                        Optional.of(AuthoredPlatforms.empty())));
    }

    @Test
    void omitsExplicitRepositoryDefaultsAndRoundTripsTheirNormalizedMeaning() {
        DependencyRepository alpha = DependencyRepository.unauthenticated(
                url("https://repo.example.com/alpha"));
        AuthoredDependencyRepositories repositories = new AuthoredDependencyRepositories(
                Optional.of(new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Enabled()),
                        Optional.of(List.of(id("alpha"), id("central"))))),
                Map.of(id("alpha"), alpha));

        String output = write(
                Optional.empty(),
                Optional.of(repositories),
                Optional.empty(),
                Optional.empty());

        assertEquals(
                """
                [repositories.alpha]
                url = "https://repo.example.com/alpha"
                """,
                output);
        AuthoredDependencyRepositories normalized = decodeAuthoredManifest(
                        "[project]\nname = \"round-trip\"\n\n" + output)
                .repositories()
                .orElseThrow();
        assertEquals(Optional.empty(), normalized.control());
        assertEquals(Map.of(id("alpha"), alpha), normalized.named());
        assertEquals(repositories.lookupOrder(), normalized.lookupOrder());
    }

    @Test
    void emitsDisabledCentralButOmitsItsDefaultEmptyOrder() {
        AuthoredDependencyRepositories repositories = new AuthoredDependencyRepositories(
                Optional.of(new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Disabled()),
                        Optional.of(List.of()))),
                Map.of());

        assertEquals(
                """
                [repositories]
                central = false
                """,
                write(
                        Optional.empty(),
                        Optional.of(repositories),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void canonicalizesFixedAndReferencedPlatformInlineSelectors() {
        AuthoredPlatforms platforms = decodeShared("""
                        [platforms]
                        "org.example:fixed" = { version = "1.5.0" }
                        "org.example:referenced" = { versionRef = "release" }
                        """)
                .platforms()
                .orElseThrow();

        String output = write(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(platforms));

        assertEquals(
                """
                [platforms]
                "org.example:fixed" = "1.5.0"
                "org.example:referenced" = { versionRef = "release" }
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
    }

    private static String write(
            Optional<AuthoredVersionAliases> versions,
            Optional<AuthoredDependencyRepositories> repositories,
            Optional<AuthoredCredentials> credentials,
            Optional<AuthoredPlatforms> platforms) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestSharedWriter().write(
                emitter, versions, repositories, credentials, platforms);
        return emitter.finish();
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }

    private static RepositoryUrl url(String value) {
        return new RepositoryUrl(value);
    }

    private static EnvironmentVariableName environment(String value) {
        return new EnvironmentVariableName(value);
    }
}
