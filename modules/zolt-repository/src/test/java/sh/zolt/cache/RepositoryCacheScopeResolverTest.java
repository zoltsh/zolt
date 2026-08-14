package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAuthentication;

final class RepositoryCacheScopeResolverTest {
    private static final String CONFIG_IDENTITY =
            "repository\tprivate\thttps://repo.example/maven2\tcreds\ncredential\tcreds\ttoken\tTOKEN";

    @Test
    void separatesResolvedBearerContextsWithoutPersistingSecrets(@TempDir Path cacheRoot) throws IOException {
        RepositoryCacheScopeResolver firstInvocation = new RepositoryCacheScopeResolver(cacheRoot);
        RepositoryCacheScope principalA = firstInvocation.resolve(CONFIG_IDENTITY, access("principal-a-token"));
        RepositoryCacheScope principalB =
                new RepositoryCacheScopeResolver(cacheRoot).resolve(CONFIG_IDENTITY, access("principal-b-token"));
        RepositoryCacheScope principalAAgain =
                new RepositoryCacheScopeResolver(cacheRoot).resolve(CONFIG_IDENTITY, access("principal-a-token"));

        assertNotEquals(principalA, principalB);
        assertEquals(principalA, principalAAgain);

        String tokenAHash = sha256("principal-a-token");
        String tokenBHash = sha256("principal-b-token");
        try (var files = Files.walk(cacheRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertFalse(text.contains("principal-a-token"));
                assertFalse(text.contains("principal-b-token"));
                assertFalse(text.contains(tokenAHash));
                assertFalse(text.contains(tokenBHash));
            }
        }
    }

    @Test
    void preservesHistoricalScopeForUnauthenticatedRepositories(@TempDir Path cacheRoot) {
        RepositoryAccess central = new RepositoryAccess(
                "central",
                URI.create("https://repo.maven.apache.org/maven2"),
                Optional.empty());

        RepositoryCacheScope scope =
                new RepositoryCacheScopeResolver(cacheRoot).resolve("central-config", List.of(central));

        assertEquals(RepositoryCacheScope.of("central-config"), scope);
        assertFalse(Files.exists(cacheRoot.resolve("identity/v1/credential-context.key")));
    }

    private static List<RepositoryAccess> access(String token) {
        return List.of(new RepositoryAccess(
                "private",
                URI.create("https://repo.example/maven2"),
                Optional.of(RepositoryAuthentication.bearer(token))));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
