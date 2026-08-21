package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

final class ManifestTomlValueEncoderTest {
    @Test
    void emitsCanonicalBasicStringsAndRoundTripsTheirValues() {
        String value = "quote \" slash \\ snowman ☃ rocket 🚀";

        String encoded = ManifestTomlValueEncoder.basicString(value);

        assertEquals("\"quote \\\" slash \\\\ snowman ☃ rocket 🚀\"", encoded);
        assertEquals(value, Toml.parse("value = " + encoded).getString("value"));
    }

    @Test
    void usesShortEscapesAndUnicodeEscapesForForbiddenControlCharacters() {
        String value = "\u0000\u0001\b\t\n\u000B\f\r\u001F\u007F";

        String encoded = ManifestTomlValueEncoder.basicString(value);

        assertEquals(
                "\"\\u0000\\u0001\\b\\t\\n\\u000B\\f\\r\\u001F\\u007F\"",
                encoded);
        assertEquals(value, Toml.parse("value = " + encoded).getString("value"));
    }

    @Test
    void escapesEveryControlCharacterForbiddenInATomlBasicString() {
        for (int codePoint = 0; codePoint <= 0x1F; codePoint++) {
            assertControlCharacterRoundTrip((char) codePoint);
        }
        assertControlCharacterRoundTrip((char) 0x7F);
    }

    @Test
    void alwaysQuotesDynamicKeysWithTheSameBasicStringRules() {
        String encoded = ManifestTomlValueEncoder.quotedKey("org.example:a\\b\"c");

        assertEquals("\"org.example:a\\\\b\\\"c\"", encoded);
        assertFalse(Toml.parse("[dependencies]\n" + encoded + " = true\n").hasErrors());
    }

    @Test
    void emitsBooleansAndTheIntegerAndDecimalShapesUsedByAuthoredModels() {
        assertEquals("true", ManifestTomlValueEncoder.booleanValue(true));
        assertEquals("false", ManifestTomlValueEncoder.booleanValue(false));
        assertEquals("21", ManifestTomlValueEncoder.integer(21));
        assertEquals("-4", ManifestTomlValueEncoder.integer(-4));
        assertEquals("74.5", ManifestTomlValueEncoder.decimal(74.5));
        assertEquals("88", ManifestTomlValueEncoder.decimal(88.0));
        assertEquals("0", ManifestTomlValueEncoder.decimal(-0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValueEncoder.decimal(Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValueEncoder.decimal(Double.POSITIVE_INFINITY));
    }

    @Test
    void joinsArraysAndInlineObjectsWithoutIntroducingPhysicalLines() {
        String resources = ManifestTomlValueEncoder.array(List.of(
                ManifestTomlValueEncoder.basicString("database"),
                ManifestTomlValueEncoder.basicString("message-bus")));
        String lock = ManifestTomlValueEncoder.inlineObject(List.of(
                ManifestTomlValueEncoder.member(
                        "class", ManifestTomlValueEncoder.basicString("com.example.DbTest")),
                ManifestTomlValueEncoder.member("resources", resources),
                ManifestTomlValueEncoder.quotedMember(
                        "external.key", ManifestTomlValueEncoder.basicString("literal"))));
        String locks = ManifestTomlValueEncoder.array(List.of(lock));

        assertEquals(
                "[{ class = \"com.example.DbTest\", resources = "
                        + "[\"database\", \"message-bus\"], \"external.key\" = \"literal\" }]",
                locks);
        assertFalse(locks.contains("\n"));
        assertFalse(Toml.parse("locks = " + locks).hasErrors());
    }

    @Test
    void rejectsFragmentsThatCouldViolateCanonicalOneLineOutput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValueEncoder.array(List.of("true\nfalse")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValueEncoder.member("not.bare", "true"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValueEncoder.inlineObject(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValueEncoder.basicString("broken\uD800"));
    }

    private static void assertControlCharacterRoundTrip(char character) {
        String value = Character.toString(character);
        String encoded = ManifestTomlValueEncoder.basicString(value);

        assertFalse(encoded.contains("\n"));
        assertFalse(encoded.contains("\r"));
        assertEquals(value, Toml.parse("value = " + encoded).getString("value"));
    }
}
