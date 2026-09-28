package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceTokenSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class PackageResourceEvidence {
    ResourceEvidence collect(Path projectRoot, BuildSettings build) {
        ResourceFilteringSettings filtering = build.resourceFiltering();
        List<Path> inputs = resourceInputs(projectRoot, build);
        return new ResourceEvidence(
                filtering,
                tokenSources(filtering.tokens()),
                resourceFingerprint(projectRoot, filtering, inputs),
                inputs);
    }

    private static List<TokenSource> tokenSources(Map<String, ResourceTokenSettings> tokens) {
        return tokens.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TokenSource(entry.getKey(), tokenSource(entry.getValue())))
                .toList();
    }

    private static String tokenSource(ResourceTokenSettings token) {
        if (token.value().isPresent()) {
            return "literal";
        }
        if (token.env().isPresent()) {
            return "env";
        }
        return "project";
    }

    private static List<Path> resourceInputs(Path projectRoot, BuildSettings build) {
        List<Path> resources = new ArrayList<>();
        for (String configuredRoot : build.resourceRoots()) {
            Path resourceRoot = resourceRoot(projectRoot, configuredRoot);
            if (!Files.isDirectory(resourceRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(resourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !isSource(path))
                        .sorted(Comparator.comparing(path -> displayPath(projectRoot, path)))
                        .forEach(resources::add);
            } catch (IOException exception) {
                throw new PackageException(
                        "Could not fingerprint resources under "
                                + resourceRoot
                                + ". Check that resource files are readable and retry.",
                        exception);
            }
        }
        return List.copyOf(resources);
    }

    private static Path resourceRoot(Path projectRoot, String configuredRoot) {
        try {
            return ProjectPaths.existingRoot(projectRoot, "[resources].main", configuredRoot);
        } catch (ProjectPathException exception) {
            throw new PackageException(exception.getMessage(), exception);
        }
    }

    private static boolean isSource(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".groovy")
                || fileName.endsWith(".kt");
    }

    private static String resourceFingerprint(
            Path projectRoot,
            ResourceFilteringSettings filtering,
            List<Path> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateText(digest, "zolt-resource-filtering-v2\n");
            updateText(digest, "enabled=" + filtering.enabled() + "\n");
            updateText(digest, "testEnabled=" + filtering.testEnabled() + "\n");
            updateText(digest, "missing=" + filtering.missing().configValue() + "\n");
            for (String include : filtering.includes().stream().sorted().toList()) {
                updateText(digest, "include=" + include + "\n");
            }
            for (TokenSource token : tokenSources(filtering.tokens())) {
                updateText(digest, "token=" + token.name() + ":" + token.source() + "\n");
            }
            for (Path resource : resources) {
                updateText(digest, "resource=" + displayPath(projectRoot, resource) + "\n");
                digest.update(Files.readAllBytes(resource));
                updateText(digest, "\n");
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint package resource inputs. Check that resource files are readable and retry.",
                    exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new PackageException("Could not fingerprint package resources because SHA-256 is unavailable.", exception);
        }
    }

    private static String displayPath(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString();
    }

    private static void updateText(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    record ResourceEvidence(
            ResourceFilteringSettings filtering,
            List<TokenSource> tokenSources,
            String fingerprint,
            List<Path> inputs) {
        ResourceEvidence {
            tokenSources = List.copyOf(tokenSources);
            inputs = List.copyOf(inputs);
        }
    }

    record TokenSource(String name, String source) {
    }
}
