package sh.zolt.publish;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Produces detached, ASCII-armored GPG signatures ({@code .asc}) by shelling out to the {@code gpg}
 * binary — the Maven-ecosystem standard, chosen over embedding crypto in the native image. When a
 * passphrase is configured it is fed to gpg over stdin ({@code --passphrase-fd 0}), never as a
 * command-line argument, so it cannot leak through the process table. When no passphrase env is
 * configured, signing relies on {@code gpg-agent}.
 *
 * <p>When {@code SOURCE_DATE_EPOCH} is set — the repo-wide reproducible-build anchor, as honored by
 * {@code BuildProvenanceReader} and {@code SbomTimestamp} — gpg's clock is frozen to that instant
 * ({@code --faked-system-time <epoch>!}) so the signature's embedded creation time, and therefore
 * the signed bundle, is byte-for-byte reproducible. Reproducible signing additionally requires a
 * pinned {@link PublishSigningSettings#keyId()}: gpg's default-key selection can differ between
 * keyrings, so an unpinned key would make which key signs environment-dependent; publish refuses to
 * sign reproducibly without one rather than ship that ambiguity.
 */
public final class PublishSigner {
    private static final String SOURCE_DATE_EPOCH = "SOURCE_DATE_EPOCH";

    private final PublishSigningSettings settings;
    private final Function<String, String> environment;
    private final String gpgExecutable;

    public PublishSigner(PublishSigningSettings settings, Function<String, String> environment) {
        this(settings, environment, "gpg");
    }

    PublishSigner(PublishSigningSettings settings, Function<String, String> environment, String gpgExecutable) {
        this.settings = settings;
        this.environment = environment;
        this.gpgExecutable = gpgExecutable;
    }

    /** Signs {@code file}, writing {@code <file>.asc} beside it and returning that path. */
    public Path sign(Path file) {
        Optional<Long> fakedTime = deterministicSigningTime();
        requirePinnedKeyForReproducibleSigning(fakedTime, file);
        Path signature = file.resolveSibling(file.getFileName() + ".asc");
        try {
            Files.deleteIfExists(signature);
        } catch (IOException exception) {
            throw new PublishException("Could not remove stale signature at " + signature + ".", exception);
        }
        Optional<String> passphrase = passphrase();
        if (fakedTime.isPresent()) {
            signDeterministically(file, signature, passphrase, fakedTime.orElseThrow());
        } else {
            run(command(file, signature, passphrase.isPresent(), Optional.empty(), null), passphrase, file);
        }
        if (!Files.isRegularFile(signature)) {
            throw new PublishException(
                    "gpg did not produce a signature for " + file
                            + ". Next: verify the signing key and gpg configuration.");
        }
        return signature;
    }

    private void signDeterministically(Path file, Path signature, Optional<String> passphrase, long epoch) {
        Path status;
        try {
            status = Files.createTempFile("zolt-signing-status", ".txt");
        } catch (IOException exception) {
            throw new PublishException("Could not prepare gpg signing status. Check the temporary directory.", exception);
        }
        RuntimeException failure = null;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                Files.writeString(status, "");
                run(command(file, signature, passphrase.isPresent(), Optional.of(epoch), status), passphrase, file);
                if (signatureTimestamp(status) == epoch) {
                    return;
                }
                Files.deleteIfExists(signature);
                // GnuPG ignores '!' when the epoch equals its startup second (T6222).
                // Once signing has advanced past that second, one retry can freeze it.
            }
            throw new PublishException("gpg signature time did not match SOURCE_DATE_EPOCH after two attempts. "
                    + "Next: check the system clock and GnuPG version; no signature was accepted.");
        } catch (IOException exception) {
            failure = new PublishException("Could not read or reset gpg signing status.", exception);
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure != null) {
                cleanSigningFile(signature, failure);
            }
            cleanSigningFile(status, failure);
        }
    }

    private static long signatureTimestamp(Path status) throws IOException {
        Long timestamp = null;
        for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\s+");
            if (fields.length < 2 || !fields[0].equals("[GNUPG:]") || !fields[1].equals("SIG_CREATED")) {
                continue;
            }
            if (timestamp != null || fields.length < 8 || !fields[2].equals("D")
                    || !fields[3].matches("[0-9]+") || !fields[4].matches("[0-9]+")
                    || !fields[5].matches("[0-9a-fA-F]{2}") || !fields[6].matches("[0-9]+")
                    || !fields[7].matches("[0-9a-fA-F]+")) {
                throw new PublishException("gpg returned malformed or ambiguous SIG_CREATED status. "
                        + "Next: check the GnuPG version; no signature was accepted.");
            }
            try {
                timestamp = Long.parseLong(fields[6]);
            } catch (NumberFormatException exception) {
                throw new PublishException("gpg returned an invalid signature timestamp.", exception);
            }
        }
        if (timestamp == null) {
            throw new PublishException("gpg did not report a detached SIG_CREATED status. "
                    + "Next: check the GnuPG version; no signature was accepted.");
        }
        return timestamp;
    }

    private static void cleanSigningFile(Path path, RuntimeException failure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
            } else {
                throw new PublishException("Could not remove signing status file " + path + ".", exception);
            }
        }
    }

    /**
     * Verifies, before any artifact leaves the machine, that signing can actually run — a missing
     * {@code gpg} binary, an unusable or (under {@code SOURCE_DATE_EPOCH}) unpinned key, or an unset
     * passphrase surfaces here rather than mid-upload, where it would strand a member half-published in
     * a repository. It signs a throwaway probe and discards the signature, reusing {@link #sign(Path)}
     * verbatim so the exact gpg invocation — including the reproducible-signing path — is exercised, not
     * re-implemented. Throws {@link PublishException} on any signing precondition failure.
     */
    public void preflight() {
        Path probe;
        try {
            probe = Files.createTempFile("zolt-signing-preflight", ".probe");
            Files.writeString(probe, "zolt signing preflight\n");
        } catch (IOException exception) {
            throw new PublishException(
                    "Could not prepare a signing preflight probe. Next: check that the temp directory is writable.",
                    exception);
        }
        try {
            Files.deleteIfExists(sign(probe));
        } catch (IOException exception) {
            throw new PublishException("Could not clean up the signing preflight probe signature.", exception);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // A leftover probe in the temp directory is harmless.
            }
        }
    }

    /**
     * The frozen signing instant when {@code SOURCE_DATE_EPOCH} pins a valid epoch, otherwise empty
     * (wall-clock signing). Delegates to {@link SourceDateEpoch}, which rejects a blank, malformed, or
     * negative value with an actionable {@link PublishException} rather than silently disabling
     * reproducibility &mdash; so a signed bundle is never quietly non-reproducible while the
     * environment claims otherwise.
     */
    private Optional<Long> deterministicSigningTime() {
        return SourceDateEpoch.parse(environment).epochSeconds();
    }

    /**
     * Reproducible signing must pin the key: with a frozen signing time but no {@code keyId}, gpg's
     * default-key selection (which can differ between keyrings) would silently decide which key
     * signs, so a "reproducible" bundle could be signed by a different key in another environment.
     * Fail with an actionable error instead of shipping that ambiguity.
     */
    private void requirePinnedKeyForReproducibleSigning(Optional<Long> fakedTime, Path file) {
        if (fakedTime.isPresent() && settings.keyId().isEmpty()) {
            throw new PublishException(
                    "Reproducible signing needs a pinned key. " + SOURCE_DATE_EPOCH
                            + " is set, which freezes the signature time so the signed bundle is"
                            + " byte-for-byte reproducible, but [publish.signing].keyId is not set — gpg's"
                            + " default-key selection can differ between keyrings, so which key signs " + file
                            + " would be environment-dependent. Next: set [publish.signing].keyId to the"
                            + " signing key's id or fingerprint.");
        }
    }

    private List<String> command(
            Path file, Path signature, boolean loopbackPassphrase, Optional<Long> fakedTime, Path statusFile) {
        List<String> command = new ArrayList<>();
        command.add(gpgExecutable);
        command.add("--batch");
        command.add("--yes");
        command.add("--armor");
        if (statusFile != null) {
            command.add("--status-file");
            command.add(statusFile.toString());
        }
        // Freeze gpg's clock so the detached signature's creation time is deterministic. The trailing
        // '!' pins the faked time instead of letting it advance with the real clock, so repeated
        // signings of identical bytes stay byte-for-byte identical. Only added in the reproducible
        // path; wall-clock signing is unchanged. No --ignore-time-conflict: the reproducible signing
        // time is the release commit's, on or after the key's creation, so gpg signs and the
        // signature verifies cleanly — ignoring the conflict would instead emit a signature predating
        // the key that verifiers (including Central) reject.
        fakedTime.ifPresent(epoch -> {
            command.add("--faked-system-time");
            command.add(epoch + "!");
        });
        settings.keyId().ifPresent(keyId -> {
            command.add("--local-user");
            command.add(keyId);
        });
        if (loopbackPassphrase) {
            command.add("--pinentry-mode");
            command.add("loopback");
            command.add("--passphrase-fd");
            command.add("0");
        }
        command.add("--output");
        command.add(signature.toString());
        command.add("--detach-sign");
        command.add(file.toString());
        return command;
    }

    private Optional<String> passphrase() {
        if (settings.passphraseEnv().isEmpty()) {
            return Optional.empty();
        }
        String name = settings.passphraseEnv().orElseThrow();
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new PublishException(
                    "Signing passphrase environment variable " + name + " is not set. Next: export " + name
                            + " or remove [publish.signing].passphraseEnv to sign through gpg-agent.");
        }
        return Optional.of(value);
    }

    private void run(List<String> command, Optional<String> passphrase, Path file) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // Honor an explicit GNUPGHOME so signing keys resolve to the configured keyring; when unset,
        // gpg falls back to its default home inherited from the parent process.
        String gnupgHome = environment.apply("GNUPGHOME");
        if (gnupgHome != null && !gnupgHome.isBlank()) {
            builder.environment().put("GNUPGHOME", gnupgHome);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new PublishException(
                    "Could not run gpg to sign " + file
                            + ". Next: install GnuPG (gpg) or disable [publish.signing].",
                    exception);
        }
        try (OutputStream stdin = process.getOutputStream()) {
            if (passphrase.isPresent()) {
                stdin.write(passphrase.orElseThrow().getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            }
        } catch (IOException ignored) {
            // gpg may already have exited; the exit code below carries the real failure.
        }
        String output;
        int exitCode;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            exitCode = process.waitFor();
        } catch (IOException exception) {
            throw new PublishException("Could not read gpg output while signing " + file + ".", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishException("gpg signing was interrupted. Try publishing again.", exception);
        }
        if (exitCode != 0) {
            throw new PublishException(
                    "gpg failed to sign " + file + " (exit code " + exitCode
                            + "). Next: check the signing key, passphrase, and gpg-agent.\n"
                            + output.stripTrailing());
        }
    }
}
