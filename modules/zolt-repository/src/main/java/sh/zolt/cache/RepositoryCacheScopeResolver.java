package sh.zolt.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import sh.zolt.lockfile.CacheRelativePath;
import sh.zolt.maven.repository.RepositoryAccess;

/** Derives persistent cache scopes from configuration plus keyed resolved credential context. */
public final class RepositoryCacheScopeResolver {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final CacheRelativePath KEY_PATH =
            new CacheRelativePath("identity/v1/credential-context.key");

    private final Path cacheRoot;

    public RepositoryCacheScopeResolver(Path cacheRoot) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    /**
     * Preserves the historical unauthenticated scope. Authenticated configurations add HMAC-based
     * discriminators derived from the actual Authorization values resolved for this invocation.
     */
    public RepositoryCacheScope resolve(
            String repositoryConfigurationIdentity,
            List<RepositoryAccess> repositories) {
        List<RepositoryAccess> authenticated = repositories.stream()
                .filter(access -> access.authentication().isPresent())
                .toList();
        if (authenticated.isEmpty()) {
            return RepositoryCacheScope.of(repositoryConfigurationIdentity);
        }
        byte[] key = cacheKey();
        StringBuilder identity = new StringBuilder(repositoryConfigurationIdentity)
                .append("\ncredential-context=v1");
        for (RepositoryAccess access : authenticated) {
            identity.append('\n')
                    .append(access.id())
                    .append('\t')
                    .append(access.uri().normalize())
                    .append('\t')
                    .append(discriminator(key, access));
        }
        return RepositoryCacheScope.of(identity.toString());
    }

    private byte[] cacheKey() {
        Path path = KEY_PATH.resolveWithin(cacheRoot);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return readKey(path);
        }
        try {
            Files.createDirectories(path.getParent());
            Path temporary = createPrivateTemporary(path.getParent());
            try {
                byte[] generated = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(generated);
                writeKey(temporary, generated);
                try {
                    Files.move(temporary, path);
                    return generated;
                } catch (FileAlreadyExistsException exception) {
                    Files.deleteIfExists(temporary);
                    return readKey(path);
                }
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not create repository credential-context key at " + path + ".",
                    exception);
        }
    }

    private static Path createPrivateTemporary(Path directory) throws IOException {
        try {
            return Files.createTempFile(
                    directory,
                    "credential-context-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile(directory, "credential-context-", ".tmp");
        }
    }

    private static void writeKey(Path path, byte[] key) throws IOException {
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(key);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            output.force(true);
        }
    }

    private static byte[] readKey(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactCacheException(
                    "Repository credential-context key at " + path + " is not a regular file.");
        }
        try {
            byte[] key = Files.readAllBytes(path);
            if (key.length != KEY_BYTES) {
                throw new ArtifactCacheException(
                        "Repository credential-context key at " + path + " is invalid. Delete it and retry.");
            }
            return key;
        } catch (IOException exception) {
            throw new ArtifactCacheException(
                    "Could not read repository credential-context key at " + path + ".",
                    exception);
        }
    }

    private static String discriminator(byte[] key, RepositoryAccess access) {
        String header = access.authentication().orElseThrow().authorizationHeaderValue();
        String context = access.id() + "\u0000" + access.uri().normalize() + "\u0000" + header;
        try {
            Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
            hmac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(hmac.doFinal(context.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new ArtifactCacheException("Could not derive repository credential-context identity.", exception);
        }
    }
}
