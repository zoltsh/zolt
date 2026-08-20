package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.toml.ZoltConfigException;

final class ManifestDependencyRepositoriesDecoderTest {
    @Test
    void decodesNamedRepositoriesWithoutMaterializingControlsOrReferences() {
        AuthoredDependencyRepositories repositories = decode("""
                [repositories.zeta]
                url = "https://repo.example.com/zeta"
                credentials = "not-declared-here"

                [repositories.alpha]
                url = "https://repo.example.com/alpha"
                """);

        assertEquals(Optional.empty(), repositories.control());
        assertEquals(
                List.of(new LocalId("alpha"), new LocalId("zeta")),
                List.copyOf(repositories.named().keySet()));
        assertEquals(
                new LocalId("not-declared-here"),
                repositories.named().get(new LocalId("zeta")).credentials().orElseThrow());
        assertTrue(repositories.centralEnabled());
    }

    @Test
    void decodesEveryCentralControlBranch() {
        CentralRepositoryControl enabled = central("true");
        CentralRepositoryControl disabled = central("false");
        CentralRepositoryControl.Replacement scalar = assertInstanceOf(
                CentralRepositoryControl.Replacement.class,
                central("\"https://mirror.example.com/maven\""));
        CentralRepositoryControl.Replacement inline = assertInstanceOf(
                CentralRepositoryControl.Replacement.class,
                central("{ url = \"https://mirror.example.com/maven\", credentials = \"company\" }"));

        assertInstanceOf(CentralRepositoryControl.Enabled.class, enabled);
        assertInstanceOf(CentralRepositoryControl.Disabled.class, disabled);
        assertEquals("https://mirror.example.com/maven", scalar.url().value());
        assertEquals(Optional.empty(), scalar.credentials());
        assertEquals(new LocalId("company"), inline.credentials().orElseThrow());
    }

    @Test
    void requiresNamedRepositoryUrlsAtTheirConcreteChildPaths() {
        assertFailure("""
                [repositories.company]
                credentials = "release"
                """, "Missing required manifest field `repositories.company.url`.");
    }

    @Test
    void anchorsRepositoryAndCentralValuesToExactScalarOrNestedPaths() {
        assertFailure("""
                [repositories.company]
                url = "http://repo.example.com/maven"
                """, "Invalid value for `repositories.company.url`: Invalid repository URL");
        assertFailure("""
                [repositories.company]
                url = "https://repo.example.com/maven"
                credentials = "Bad_Id"
                """, "Invalid value for `repositories.company.credentials`: Invalid local ID");
        assertFailure("""
                [repositories]
                central = "http://mirror.example.com/maven"
                """, "Invalid value for `repositories.central`: Invalid repository URL");
        assertFailure("""
                [repositories]
                central = { url = "http://mirror.example.com/maven" }
                """, "Invalid value for `repositories.central.url`: Invalid repository URL");
        assertFailure("""
                [repositories]
                central = { url = "https://mirror.example.com/maven", credentials = "Bad_Id" }
                """, "Invalid value for `repositories.central.credentials`: Invalid local ID");
    }

    @Test
    void retainsExactAuthoredOrderAndValidatesTheCompleteUniverse() {
        AuthoredDependencyRepositories repositories = decode("""
                [repositories]
                order = ["zeta", "alpha", "central"]

                [repositories.alpha]
                url = "https://repo.example.com/alpha"

                [repositories.zeta]
                url = "https://repo.example.com/zeta"
                """);

        assertEquals(
                List.of(new LocalId("zeta"), new LocalId("alpha"), new LocalId("central")),
                repositories.lookupOrder());
        assertFailure("""
                [repositories]
                order = ["alpha", "alpha", "central"]

                [repositories.alpha]
                url = "https://repo.example.com/alpha"
                """, "Invalid manifest section `[repositories]`: Repository order lists `alpha` more than once");
        assertFailure("""
                [repositories]
                order = ["alpha"]

                [repositories.alpha]
                url = "https://repo.example.com/alpha"
                """, "expected [alpha, central] but got [alpha]");
    }

    @Test
    void permitsAnExplicitlyDisabledEmptyRepositoryUniverse() {
        AuthoredDependencyRepositories repositories = decode("""
                [repositories]
                central = false
                order = []
                """);

        assertFalse(repositories.centralEnabled());
        assertTrue(repositories.named().isEmpty());
        assertTrue(repositories.lookupOrder().isEmpty());
    }

    @Test
    void rejectsCentralInAnOrderWhenCentralIsDisabled() {
        assertFailure("""
                [repositories]
                central = false
                order = ["company", "central"]

                [repositories.company]
                url = "https://repo.example.com/company"
                """, "expected [company] but got [central, company]");
    }

    @Test
    void leavesReservedIdsAndEmptyControlShapeToValidation() {
        assertFailure("""
                [repositories.central]
                url = "https://repo.example.com/maven"
                """, "Manifest ID `central` is reserved");
        assertFailure("[repositories]\n", "Manifest table `[repositories]` must not be empty");
    }

    private static CentralRepositoryControl central(String value) {
        return decode("[repositories]\ncentral = " + value + "\n")
                .control()
                .orElseThrow()
                .central()
                .orElseThrow();
    }

    private static AuthoredDependencyRepositories decode(String source) {
        return new ManifestDependencyRepositoriesDecoder()
                .decode(ManifestSemanticTestSupport.index(source))
                .orElseThrow();
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
