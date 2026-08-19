package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AuthoredVersionAliasesTest {
    @Test
    void retainsFixedAliasesInNormalizedIdOrderAndIsImmutable() {
        LinkedHashMap<LocalId, VersionAliasValue> source = new LinkedHashMap<>();
        source.put(new LocalId("spring-boot"), new VersionAliasValue("4.0.6"));
        source.put(new LocalId("junit"), new VersionAliasValue("5.13.4"));

        AuthoredVersionAliases aliases = new AuthoredVersionAliases(source);
        source.clear();

        assertEquals(List.of(new LocalId("junit"), new LocalId("spring-boot")),
                List.copyOf(aliases.entries().keySet()));
        assertEquals("5.13.4", aliases.entries().get(new LocalId("junit")).value());
        assertThrows(
                UnsupportedOperationException.class,
                () -> aliases.entries().put(new LocalId("netty"), new VersionAliasValue("4.1.119.Final")));
    }

    @Test
    void acceptsDeferredSnapshotAliasesWhileRejectingOtherNonliteralValues() {
        assertEquals("1.0-SNAPSHOT", new VersionAliasValue("1.0-SNAPSHOT").value());

        for (String value : List.of("", "[1.0,2.0)", "1.+", "LATEST", "${junit}", "1.0.")) {
            assertThrows(IllegalArgumentException.class, () -> new VersionAliasValue(value), value);
        }
    }

    @Test
    void requiresFinalLanguageLocalIdsAndNonNullEntries() {
        assertThrows(IllegalArgumentException.class, () -> new LocalId("SpringBoot"));
        assertThrows(NullPointerException.class,
                () -> new AuthoredVersionAliases(Map.of(new LocalId("junit"), null)));
    }
}
