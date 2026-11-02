package sh.zolt.build.output;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Portable compiler ownership, captured before resource and metadata overlays. */
public final class CompilerOutputInventory {
    public static final String FILE_NAME = ".zolt-compiler-outputs.state";
    private static final String HEADER = "zolt-compiler-outputs-v1";
    private static final Set<String> STATE_FILES = Set.of(
            FILE_NAME, ".zolt-resources.state", ".zolt-build-main.fingerprint",
            ".zolt-build-test.fingerprint", ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint.state", ".zolt-incremental-main.state",
            ".zolt-incremental-test.state");

    private CompilerOutputInventory() {}

    public record Snapshot(Map<Path, String> files, Set<Path> overlays) {
        public Snapshot {
            files = Map.copyOf(files);
            overlays = Set.copyOf(overlays);
        }
    }

    public static boolean stateFile(Path path) {
        return STATE_FILES.contains(path.getFileName().toString());
    }

    /** A missing, truncated, escaping, or otherwise malformed roster must never authorize reuse. */
    public static Optional<Snapshot> read(Path output) {
        Path root = output.toAbsolutePath().normalize();
        Path state = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            validate(root, Path.of(FILE_NAME), true);
            String text = Files.readString(state, StandardCharsets.UTF_8);
            int trailer = text.lastIndexOf("sha256\t");
            if (!text.startsWith(HEADER + "\n") || trailer < 0 || !text.endsWith("\n")
                    || !hash(text.substring(0, trailer).getBytes(StandardCharsets.UTF_8))
                            .equals(text.substring(trailer + 7, text.length() - 1))) {
                return Optional.empty();
            }
            Map<Path, String> files = new LinkedHashMap<>();
            Set<Path> overlays = new LinkedHashSet<>();
            for (String line : text.substring(HEADER.length() + 1, trailer).lines().toList()) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) {
                    return Optional.empty();
                }
                Path path = Path.of(new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8));
                validate(root, path, false);
                if (parts.length == 3 && parts[0].equals("file") && parts[2].matches("[0-9a-f]{64}")) {
                    if (files.putIfAbsent(path, parts[2]) != null) {
                        return Optional.empty();
                    }
                } else if (parts.length == 2 && parts[0].equals("overlay")) {
                    if (!overlays.add(path)) {
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
            }
            return Optional.of(new Snapshot(files, overlays));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static void captureIfCompiled(Path output, String mode, boolean compiled) {
        if (compiled) {
            capture(output, "incremental".equals(mode));
        }
    }

    /** Called only after real compilation, while compiler bytes are still unobscured. */
    public static void capture(Path output, boolean incremental) {
        Set<Path> previousOverlays = incremental
                ? read(output).orElseThrow(() -> new BuildException("Missing compiler output ownership before selective compilation."))
                        .overlays()
                : Set.of();
        Map<Path, String> files = new LinkedHashMap<>();
        for (Path path : outputFiles(output)) {
            if (!previousOverlays.contains(path)) {
                files.put(path, fileHash(output.resolve(path)));
            }
        }
        write(output, new Snapshot(files, Set.of()));
    }

    /** Records overlay ownership without ever reclassifying or rehashing compiler output. */
    public static void recordOverlays(Path output, Set<Path> overlays) {
        Snapshot previous = read(output).orElseThrow(() -> new BuildException(
                "Missing or invalid compiler output inventory under " + output + ". Rebuild the project."));
        write(output, new Snapshot(previous.files(), overlays));
    }

    public static boolean complete(Path output) {
        try {
            return read(output).filter(snapshot -> snapshot.files().entrySet().stream().allMatch(entry ->
                    Files.isRegularFile(output.resolve(entry.getKey()), LinkOption.NOFOLLOW_LINKS)
                            && (snapshot.overlays().contains(entry.getKey())
                                    || entry.getValue().equals(fileHash(output.resolve(entry.getKey())))))).isPresent();
        } catch (BuildException exception) {
            return false;
        }
    }

    /** Selective processors cannot distinguish old overlays from newly generated colliding files. */
    public static boolean requiresFullCompile(Path output, boolean processors) {
        Optional<Snapshot> previous = read(output);
        if (previous.isEmpty()) {
            return true;
        }
        return previous.orElseThrow().overlays().stream().anyMatch(path -> processors
                || path.toString().endsWith(".class") || previous.orElseThrow().files().containsKey(path));
    }

    public static List<Path> outputFiles(Path output) {
        Path root = output.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> all = paths.toList();
            for (Path path : all) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Compiler output must not contain symbolic links: " + path);
                }
            }
            return all.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(root::relativize).filter(path -> !stateFile(path)).sorted().toList();
        } catch (IOException exception) {
            throw new BuildException("Could not inventory compiler outputs under " + root + ".", exception);
        }
    }

    public static String fileHash(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return "missing";
        }
        try {
            return hash(Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new BuildException("Could not read compiler output " + file + ".", exception);
        }
    }

    private static void write(Path output, Snapshot snapshot) {
        Path root = output.toAbsolutePath().normalize();
        try {
            validate(root, Path.of(FILE_NAME), true);
            StringBuilder content = new StringBuilder(HEADER).append('\n');
            for (Path path : snapshot.files().keySet().stream().sorted().toList()) {
                validate(root, path, false);
                content.append("file\t").append(encode(path)).append('\t')
                        .append(snapshot.files().get(path)).append('\n');
            }
            for (Path path : snapshot.overlays().stream().sorted().toList()) {
                validate(root, path, false);
                content.append("overlay\t").append(encode(path)).append('\n');
            }
            String checksum = hash(content.toString().getBytes(StandardCharsets.UTF_8));
            content.append("sha256\t").append(checksum).append('\n');
            String updated = content.toString();
            Path state = root.resolve(FILE_NAME);
            if (Files.isRegularFile(state) && Files.readString(state).equals(updated)) {
                return;
            }
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, ".zolt-compiler-inventory-", ".tmp");
            try {
                Files.writeString(temporary, updated, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new BuildException("Could not record compiler ownership under " + root + ".", exception);
        }
    }

    private static void validate(Path root, Path relative, boolean state) throws IOException {
        String name = relative.toString().replace(java.io.File.separatorChar, '/');
        if (name.isEmpty() || relative.isAbsolute() || !relative.equals(relative.normalize())
                || relative.startsWith("..") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || (!state && stateFile(relative))) {
            throw new IOException("Invalid compiler ownership path: " + relative);
        }
        for (Path path = root.resolve(relative); path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Compiler ownership must not traverse a symbolic link: " + path);
            }
        }
    }

    private static String encode(Path path) {
        return Base64.getEncoder().encodeToString(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
