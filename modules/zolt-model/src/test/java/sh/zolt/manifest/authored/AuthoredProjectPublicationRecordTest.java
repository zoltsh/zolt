package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredProjectPublicationRecordTest {
    @Test
    void retainsScmExternalValuesWithoutUrlNormalization() {
        AuthoredProjectScm scm = new AuthoredProjectScm(
                Optional.of("ssh://Git@EXAMPLE.com/Repo/"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertEquals("ssh://Git@EXAMPLE.com/Repo/", scm.url().orElseThrow());
    }

    @Test
    void retainsDeveloperExternalValuesWithoutDomainValidation() {
        AuthoredProjectDeveloper developer = new AuthoredProjectDeveloper(
                Optional.of("Ada Lovelace"),
                Optional.of("ada at example dot com"),
                Optional.of("Analytical Engines"),
                Optional.of("profile:ada"));

        assertEquals("ada at example dot com", developer.email().orElseThrow());
        assertEquals("profile:ada", developer.url().orElseThrow());
    }

    @Test
    void rejectsEmptyOrBlankScmRecords() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredProjectScm(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredProjectScm(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("\n")));
    }

    @Test
    void rejectsEmptyOrBlankDeveloperRecords() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredProjectDeveloper(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredProjectDeveloper(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(" "),
                        Optional.empty()));
    }
}
