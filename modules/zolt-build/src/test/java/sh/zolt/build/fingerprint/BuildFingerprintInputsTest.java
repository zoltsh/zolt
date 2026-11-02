package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BuildFingerprintInputsTest {
    private static final String FINGERPRINT = """
            version=1
            projectJava=21
            compilerSettings=release=21
            [compileClasspath]
            /a.jar|hash-a
            [sources]
            src/Foo.java|hash-foo
            [expectedClasses]
            target/classes/Foo.class
            [compilerOutputHashes]
            target/classes/Foo.class|hash-class
            """;

    @Test
    void stripsTheExpectedClassesOutputSection() {
        String inputs = BuildFingerprintInputs.inputsOnly(FINGERPRINT);
        assertTrue(inputs.contains("[sources]"));
        assertTrue(inputs.contains("src/Foo.java|hash-foo"));
        assertFalse(inputs.contains("[expectedClasses]"));
        assertFalse(inputs.contains("[compilerOutputHashes]"));
        assertFalse(inputs.contains("hash-class"));
        assertFalse(inputs.contains("Foo.class"));
    }

    @Test
    void isInsensitiveToExpectedClassesButSensitiveToInputs() {
        String differentOutputs = FINGERPRINT.replace(
                "target/classes/Foo.class", "target/classes/Foo.class\ntarget/classes/Bar.class");
        assertEquals(
                BuildFingerprintInputs.inputsSha256(FINGERPRINT),
                BuildFingerprintInputs.inputsSha256(differentOutputs),
                "expected-class changes must not change the cache key");
        assertEquals(
                BuildFingerprintInputs.inputsSha256(FINGERPRINT),
                BuildFingerprintInputs.inputsSha256(FINGERPRINT.replace("hash-class", "damaged-class")));

        String differentInputs = FINGERPRINT.replace("hash-foo", "hash-foo-edited");
        assertNotEquals(
                BuildFingerprintInputs.inputsSha256(FINGERPRINT),
                BuildFingerprintInputs.inputsSha256(differentInputs),
                "a source content change must change the cache key");
    }
}
