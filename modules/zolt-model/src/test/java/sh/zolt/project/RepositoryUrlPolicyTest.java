package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RepositoryUrlPolicyTest {
    @Test
    void acceptsHttpsRepositoryUrls() {
        assertEquals(
                "https://repo.example.test/maven",
                RepositoryUrlPolicy.requireSafeUrl(
                                "Repository `company`",
                                "https://repo.example.test/maven",
                                false)
                        .toString());
    }

    @Test
    void rejectsUrlUserinfo() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryUrlPolicy.requireSafeUrl(
                        "Repository `company`",
                        "https://user:secret@repo.example.test/maven",
                        false));

        assertTrue(exception.getMessage().contains("Repository `company` URL contains embedded credentials"));
        assertTrue(exception.getMessage().contains("[credentials]"));
        assertTrue(!exception.getMessage().contains("secret"));
    }

    @Test
    void rejectsCredentialedRemoteHttp() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryUrlPolicy.requireSafeUrl(
                        "Repository `company`",
                        "http://repo.example.test/maven",
                        true));

        assertTrue(exception.getMessage().contains("Credentialed remote repositories require HTTPS"));
    }

    @Test
    void rejectsUncredentialedRemoteHttp() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryUrlPolicy.requireSafeUrl(
                        "Repository `company`",
                        "http://repo.example.test/maven",
                        false));

        assertTrue(exception.getMessage().contains("uses non-local HTTP"));
        assertTrue(exception.getMessage().contains("localhost or loopback"));
    }

    @Test
    void allowsLoopbackHttpForLocalDevelopment() {
        assertEquals(
                "http://127.0.0.1:18080/maven2",
                RepositoryUrlPolicy.requireSafeUrl(
                                "Repository `local`",
                                "http://127.0.0.1:18080/maven2",
                                false)
                        .toString());
    }
}
