package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RepositoryUrlTest {
    @Test
    void acceptsHttpsAndOnlyLoopbackHttp() {
        for (String value : List.of(
                "https://repo.example.com/maven",
                "http://localhost:8081/maven",
                "http://127.0.0.42/maven",
                "http://[::1]/maven",
                "http://[0:0:0:0:0:0:0:1]/maven")) {
            assertEquals(value, new RepositoryUrl(value).value());
        }
    }

    @Test
    void rejectsUnsafeOrNonrepositoryUrls() {
        for (String value : List.of(
                "http://repo.example.com/maven",
                "http://128.0.0.1/maven",
                "http://127.0.0.999/maven",
                "ftp://repo.example.com/maven",
                "repo.example.com/maven",
                "https:///maven",
                "https://user:secret@repo.example.com/maven",
                "https://@repo.example.com/maven",
                "https://repo.example.com/maven#snapshot",
                " https://repo.example.com/maven",
                "https://repo.example.com/maven ")) {
            assertThrows(IllegalArgumentException.class, () -> new RepositoryUrl(value), value);
        }
    }

    @Test
    void normalizesOnlyTrailingPathSlashesForIdentity() {
        RepositoryUrl url = new RepositoryUrl("https://repo.example.com/maven///?channel=releases");

        assertEquals("https://repo.example.com/maven///?channel=releases", url.value());
        assertEquals("https://repo.example.com/maven?channel=releases", url.normalizedIdentity());
    }
}
