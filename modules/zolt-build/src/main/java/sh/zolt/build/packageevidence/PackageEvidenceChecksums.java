package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PackageEvidenceChecksums {
    private PackageEvidenceChecksums() {
    }

    public static String fileSha256(Path path) {
        if (!Files.isRegularFile(path)) {
            return "missing";
        }
        return sha256(path);
    }

    static String sha256(Path path) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not read package evidence input at "
                            + path
                            + ". Check that the file is readable and retry.",
                    exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new PackageException("Could not compute package evidence checksum because SHA-256 is unavailable.", exception);
        }
    }

    static String outputSha256(Path path, String checksumKind) {
        if ("tree".equals(checksumKind)) {
            return PackageInputFingerprinting.packageLayoutFingerprint(path);
        }
        return fileSha256(path);
    }
}
