package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PublishSignerStatusTest {
    private static final long EPOCH = 1700000010;
    private static final String CREATED = "[GNUPG:] SIG_CREATED D 1 10 00 ";
    private static final String FINGERPRINT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @TempDir
    private Path tempDir;

    @Test
    void retriesOneWrongTimestampAndAcceptsOnlyTheExactEpoch() throws IOException {
        PublishSigner signer = signer(EPOCH, status(EPOCH + 1), status(EPOCH));

        Path signature = signer.sign(artifact());

        assertEquals("signature-2", Files.readString(signature));
        assertInvocations(2, EPOCH);
        assertStatusCleaned();
    }

    @Test
    void repeatedWrongTimestampRejectsAndRemovesTheSignature() throws IOException {
        PublishSigner signer = signer(EPOCH, status(EPOCH + 1), status(EPOCH + 2));
        Path artifact = artifact();

        PublishException error = assertThrows(PublishException.class, () -> signer.sign(artifact));

        assertTrue(error.getMessage().contains("after two attempts"), error.getMessage());
        assertInvocations(2, EPOCH);
        assertFalse(Files.exists(tempDir.resolve("artifact.jar.asc")));
        assertStatusCleaned();
    }

    @Test
    void retryCannotAcceptTheRejectedSignatureWhenNoNewOutputIsProduced() throws IOException {
        PublishSigner signer = signer(EPOCH, status(EPOCH + 1), status(EPOCH));
        Files.createFile(tempDir.resolve("omit-output-2"));
        Path artifact = artifact();

        PublishException error = assertThrows(PublishException.class, () -> signer.sign(artifact));

        assertTrue(error.getMessage().contains("did not produce a signature"), error.getMessage());
        assertInvocations(2, EPOCH);
        assertFalse(Files.exists(tempDir.resolve("artifact.jar.asc")));
        assertStatusCleaned();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "[GNUPG:] SIG_CREATED",
            "[GNUPG:] SIG_CREATED S 1 10 00 1700000010 AAAAAAAA",
            "[GNUPG:] SIG_CREATED D 1 10 00 not-an-epoch AAAAAAAA",
            "[GNUPG:] SIG_CREATED D 1 10 00 -1 AAAAAAAA",
            "[GNUPG:] SIG_CREATED D 1 10 00 999999999999999999999999 AAAAAAAA",
            "[GNUPG:] SIG_CREATED D bad 10 00 1700000010 AAAAAAAA",
            "[GNUPG:] SIG_CREATED D 1 10 00 1700000010",
            "[GNUPG:] SIG_CREATED D 1 10 00 1700000010 not-a-fingerprint",
            "[GNUPG:] SIG_CREATED D 1 10 00 1700000010 AAAAAAAA\n"
                    + "[GNUPG:] SIG_CREATED D 1 10 00 1700000010 AAAAAAAA"
    })
    void invalidStatusFailsImmediatelyWithoutTrustingMergedDiagnostics(String invalid) throws IOException {
        PublishSigner signer = signer(EPOCH, invalid, status(EPOCH));
        Path artifact = artifact();

        assertThrows(PublishException.class, () -> signer.sign(artifact));

        assertInvocations(1, EPOCH);
        assertFalse(Files.exists(tempDir.resolve("artifact.jar.asc")));
        assertStatusCleaned();
    }

    @Test
    void failedGpgInvocationDoesNotRetryEvenWithValidCreationStatus() throws IOException {
        PublishSigner signer = signer(EPOCH, status(EPOCH), status(EPOCH));
        Files.writeString(tempDir.resolve("exit-1"), "2\n");
        Path artifact = artifact();

        PublishException error = assertThrows(PublishException.class, () -> signer.sign(artifact));

        assertTrue(error.getMessage().contains("exit code 2"), error.getMessage());
        assertInvocations(1, EPOCH);
        assertFalse(Files.exists(tempDir.resolve("artifact.jar.asc")));
        assertStatusCleaned();
    }

    @Test
    void futureEpochRemainsExactAndUnknownStatusExtensionsAreAllowed() throws IOException {
        long futureEpoch = 4102444800L;
        PublishSigner signer = signer(futureEpoch,
                "[GNUPG:] FUTURE_STATUS ignored\n[GNUPG:] SIG_CREATED_EXTENSION ignored\n"
                        + status(futureEpoch) + " additional-future-field\n[GNUPG:] SIGNING_FINISHED");

        assertEquals("signature-1", Files.readString(signer.sign(artifact())));

        assertInvocations(1, futureEpoch);
        assertStatusCleaned();
    }

    @Test
    void ordinarySigningDoesNotRequestStatusOrFreezeTheClock() throws IOException {
        Path executable = fakeGpg();
        PublishSigner signer = new PublishSigner(
                new PublishSigningSettings(true, Optional.empty(), Optional.empty()),
                name -> null, executable.toString());

        assertEquals("signature-1", Files.readString(signer.sign(artifact())));

        List<String> arguments = Files.readAllLines(tempDir.resolve("args-1"));
        assertFalse(arguments.contains("--status-file"));
        assertFalse(arguments.contains("--faked-system-time"));
        assertFalse(Files.exists(tempDir.resolve("status-paths")));
    }

    private PublishSigner signer(long epoch, String... statuses) throws IOException {
        for (int index = 0; index < statuses.length; index++) {
            Files.writeString(tempDir.resolve("status-" + (index + 1)), statuses[index]);
        }
        return new PublishSigner(
                new PublishSigningSettings(true, Optional.of("configured-key-id"), Optional.empty()),
                Map.of("SOURCE_DATE_EPOCH", Long.toString(epoch))::get, fakeGpg().toString());
    }

    private Path artifact() throws IOException {
        return Files.writeString(tempDir.resolve("artifact.jar"), "payload\n");
    }

    private static String status(long epoch) {
        return CREATED + epoch + " " + FINGERPRINT;
    }

    private void assertInvocations(int expected, long epoch) throws IOException {
        assertEquals(Integer.toString(expected), Files.readString(tempDir.resolve("count")).strip());
        for (int invocation = 1; invocation <= expected; invocation++) {
            List<String> arguments = Files.readAllLines(tempDir.resolve("args-" + invocation));
            assertEquals(epoch + "!", arguments.get(arguments.indexOf("--faked-system-time") + 1));
            assertTrue(arguments.contains("--status-file"));
        }
    }

    private void assertStatusCleaned() throws IOException {
        for (String path : Files.readAllLines(tempDir.resolve("status-paths"))) {
            assertFalse(Files.exists(Path.of(path)), "status file must be removed: " + path);
        }
    }

    private Path fakeGpg() throws IOException {
        Path executable = tempDir.resolve("fake-gpg");
        Files.writeString(executable, """
                #!/usr/bin/env bash
                set -euo pipefail
                root="$(cd "$(dirname "$0")" && pwd)"
                count=0
                if [[ -f "$root/count" ]]; then read -r count < "$root/count"; fi
                count=$((count + 1))
                printf '%s\\n' "$count" > "$root/count"
                printf '%s\\n' "$@" > "$root/args-$count"
                status_file=""
                output=""
                while [[ "$#" -gt 0 ]]; do
                  case "$1" in
                    --status-file) status_file="$2"; shift ;;
                    --output) output="$2"; shift ;;
                  esac
                  shift
                done
                if [[ ! -f "$root/omit-output-$count" ]]; then
                  printf 'signature-%s' "$count" > "$output"
                fi
                if [[ -n "$status_file" ]]; then
                  printf '%s\\n' "$status_file" >> "$root/status-paths"
                  if [[ -f "$root/status-$count" ]]; then cat "$root/status-$count" >> "$status_file"; fi
                fi
                # Valid-looking diagnostics must never substitute for the separate status channel.
                printf '[GNUPG:] SIG_CREATED D 1 10 00 1700000010 AAAAAAAA\\n'
                if [[ -f "$root/exit-$count" ]]; then
                  read -r code < "$root/exit-$count"
                  exit "$code"
                fi
                """);
        assertTrue(executable.toFile().setExecutable(true));
        return executable;
    }
}
