package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.jetbrains.kotlin.cli.common.arguments.PreprocessCommandLineArgumentsKt;
import org.junit.jupiter.api.Test;

final class KotlinCompilerArgumentsFileTest {
    @Test
    void quotesEveryArgumentExactlyAndRoundTripsThroughThePinnedKotlinParser() {
        List<String> arguments = List.of(
                "plain",
                "with space",
                "double\"quote",
                "single'quote",
                "C:\\work\\tail\\",
                "snowman-☃",
                "@nested",
                "",
                "line1\nline2\rline3",
                "tab\tinside");

        String encoded = KotlinCompilerArgumentsFile.encode(arguments);

        assertEquals(
                "\"plain\"\n"
                        + "\"with space\"\n"
                        + "\"double\\\"quote\"\n"
                        + "\"single'quote\"\n"
                        + "\"C:\\\\work\\\\tail\\\\\"\n"
                        + "\"snowman-☃\"\n"
                        + "\"@nested\"\n"
                        + "\"\"\n"
                        + "\"line1\nline2\rline3\"\n"
                        + "\"tab\tinside\"\n",
                encoded);
        assertEquals(
                arguments,
                PreprocessCommandLineArgumentsKt.readArgumentsFromArgFile(encoded));
    }

    @Test
    void createsAnAbsoluteUtf8FileWithOwnerOnlyPosixPermissionsAndDeletesItOnClose() throws IOException {
        Path path;
        try (KotlinCompilerArgumentsFile argumentsFile =
                KotlinCompilerArgumentsFile.create(List.of("Unicode ☃ argument"))) {
            path = Path.of(argumentsFile.commandArgument().substring(1));
            assertTrue(path.isAbsolute());
            assertEquals(
                    KotlinCompilerArgumentsFile.encode(List.of("Unicode ☃ argument")),
                    Files.readString(path, StandardCharsets.UTF_8));
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                assertEquals(
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(path));
            }
        }

        assertFalse(Files.exists(path));
    }
}
