package sh.zolt.build.output;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CompilerOutputInventoryTest {
    @TempDir private Path output;

    @Test
    void overlayRecordingPreservesCompilerHashesAndSelectiveCaptureExcludesCopiedFiles() throws Exception {
        Files.writeString(output.resolve("Main.class"), "compiled");
        CompilerOutputInventory.capture(output, false);
        String hash = CompilerOutputInventory.read(output).orElseThrow().files().get(Path.of("Main.class"));
        Files.writeString(output.resolve("config.txt"), "resource");
        CompilerOutputInventory.recordOverlays(output, Set.of(Path.of("config.txt")));
        assertFalse(CompilerOutputInventory.requiresFullCompile(output, false));
        assertTrue(CompilerOutputInventory.requiresFullCompile(output, true));
        CompilerOutputInventory.capture(output, true);
        assertEquals(Set.of(Path.of("Main.class")), CompilerOutputInventory.read(output).orElseThrow().files().keySet());
        assertEquals(hash, CompilerOutputInventory.read(output).orElseThrow().files().get(Path.of("Main.class")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "/outside", "a/../outside", "C:/outside", "a\\outside", ".zolt-compiler-outputs.state"})
    void rejectsEscapingOrReservedPathsEvenWithAValidChecksum(String path) throws Exception {
        String body = "zolt-compiler-outputs-v1\nfile\t"
                + Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)) + "\t" + "0".repeat(64) + "\n";
        Files.writeString(output.resolve(CompilerOutputInventory.FILE_NAME), signed(body));
        assertTrue(CompilerOutputInventory.read(output).isEmpty());
        assertFalse(CompilerOutputInventory.complete(output));
    }

    @Test
    void rejectsTruncationAndMissingOwnedFiles() throws Exception {
        Files.writeString(output.resolve("Main.class"), "compiled");
        CompilerOutputInventory.capture(output, false);
        assertTrue(CompilerOutputInventory.complete(output));
        Path state = output.resolve(CompilerOutputInventory.FILE_NAME);
        String original = Files.readString(state);
        Files.writeString(state, original.substring(0, original.indexOf("sha256")));
        assertTrue(CompilerOutputInventory.read(output).isEmpty());
        Files.writeString(state, original);
        Files.writeString(output.resolve("Main.class"), "corrupt");
        assertFalse(CompilerOutputInventory.complete(output), "cache restore must validate unmasked compiler bytes");
        Files.delete(output.resolve("Main.class"));
        assertFalse(CompilerOutputInventory.complete(output));
    }

    @Test
    void rejectsOwnedPathsRetargetedThroughSymlink() throws Exception {
        Path nested = Files.createDirectories(output.resolve("nested"));
        Files.writeString(nested.resolve("Main.class"), "compiled");
        CompilerOutputInventory.capture(output, false);
        Files.move(nested, output.resolve("other"));
        Files.createSymbolicLink(nested, Path.of("other"));
        assertTrue(CompilerOutputInventory.read(output).isEmpty());
    }

    private static String signed(String body) throws Exception {
        return body + "sha256\t" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))) + "\n";
    }
}
