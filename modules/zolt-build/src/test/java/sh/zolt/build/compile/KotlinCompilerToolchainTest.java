package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KotlinCompilerToolchainTest {
    private static final String SHA = "a".repeat(64);
    private static final String CLOSURE = "sha256:" + "b".repeat(64);

    @Test
    void exposesNormalizedCompilerIdentityAndClasspath() {
        KotlinCompilerToolchain toolchain = new KotlinCompilerToolchain(
                " 2.2.0 ",
                SHA,
                List.of(Path.of("relative/compiler.jar")),
                CLOSURE);

        assertEquals(KotlinCompilerToolchain.COORDINATE, toolchain.coordinate());
        assertEquals("2.2.0", toolchain.version());
        assertEquals(SHA, toolchain.sha256());
        assertEquals(
                KotlinCompilerToolchain.COORDINATE + ":2.2.0@sha256:" + SHA
                        + "|launcher=" + CLOSURE,
                toolchain.identity());
        assertTrue(toolchain.launcherClasspath().entries().getFirst().isAbsolute());
    }

    @Test
    void rejectsMissingOrMalformedIdentityParts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerToolchain(" ", SHA, List.of(Path.of("compiler.jar")), CLOSURE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerToolchain("2.2.0", "A".repeat(64), List.of(Path.of("compiler.jar")), CLOSURE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerToolchain("2.2.0", SHA, List.of(), CLOSURE));
        assertThrows(
                NullPointerException.class,
                () -> new KotlinCompilerToolchain(
                        "2.2.0",
                        SHA,
                        Arrays.asList((Path) null),
                        CLOSURE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerToolchain(
                        "2.2.0",
                        SHA,
                        List.of(Path.of("compiler.jar")),
                        "sha256:short"));
    }
}
