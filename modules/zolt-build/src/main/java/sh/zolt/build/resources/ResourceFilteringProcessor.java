package sh.zolt.build.resources;

import sh.zolt.build.ResourceCopyException;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResourceFilteringProcessor {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("@([A-Za-z0-9_.-]+)@");

    private final boolean enabled;
    private final ResourceFilteringSettings filtering;
    private final Map<String, String> tokenValues;

    private ResourceFilteringProcessor(
            boolean enabled,
            ResourceFilteringSettings filtering,
            Map<String, String> tokenValues) {
        this.enabled = enabled;
        this.filtering = filtering;
        this.tokenValues = Map.copyOf(tokenValues);
    }

    static ResourceFilteringProcessor create(
            boolean enabled,
            ResourceFilteringSettings filtering,
            Optional<ProjectMetadata> project) {
        if (!enabled) {
            return new ResourceFilteringProcessor(false, filtering, Map.of());
        }
        return new ResourceFilteringProcessor(true, filtering, tokenValues(filtering.tokens(), project));
    }

    Optional<byte[]> filteredBytes(Path resource, Path relativePath) throws IOException {
        if (!enabled || !matchesFilter(relativePath, filtering.includes())) {
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(resource);
        if (containsNul(bytes)) {
            throw new ResourceCopyException(
                    "Resource `"
                            + relativePath.toString().replace('\\', '/')
                            + "` was selected for filtering but appears to be binary. Remove it from [resources.filter].include or use a text resource.");
        }
        return Optional.of(filteredBytes(resource, relativePath, bytes));
    }

    private byte[] filteredBytes(Path resource, Path relativePath, byte[] bytes) {
        String content = decodeUtf8(resource, relativePath, bytes);
        Matcher matcher = TOKEN_PATTERN.matcher(content);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String value = tokenValues.get(token);
            if (value == null) {
                if (filtering.missing() == ResourceMissingTokenPolicy.FAIL) {
                    throw new ResourceCopyException(
                            "Resource `"
                                    + relativePath.toString().replace('\\', '/')
                                    + "` contains token @"
                                    + token
                                    + "@ but [resources.tokens]."
                                    + token
                                    + " is not defined. Add the token or set [resources.filter].missing = \"keep\".");
                }
                matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(output, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean matchesFilter(Path relativePath, List<String> includes) {
        if (includes.isEmpty()) {
            return false;
        }
        String normalized = relativePath.toString().replace('\\', '/');
        return includes.stream().anyMatch(pattern -> globMatches(pattern, normalized));
    }

    private static boolean globMatches(String pattern, String normalizedPath) {
        try {
            if (java.nio.file.FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern)
                    .matches(Path.of(normalizedPath))) {
                return true;
            }
            return pattern.startsWith("**/")
                    && java.nio.file.FileSystems.getDefault()
                            .getPathMatcher("glob:" + pattern.substring(3))
                            .matches(Path.of(normalizedPath));
        } catch (IllegalArgumentException exception) {
            throw new ResourceCopyException(
                    "Invalid resource filtering include glob `"
                            + pattern
                            + "`. Use resource-relative glob patterns such as **/*.properties.",
                    exception);
        }
    }

    private static String decodeUtf8(Path resource, Path relativePath, byte[] bytes) {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new ResourceCopyException(
                    "Resource `"
                            + relativePath.toString().replace('\\', '/')
                            + "` was selected for filtering but is not valid UTF-8 text. Remove it from [resources.filter].include or convert it to UTF-8.",
                    exception);
        }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> tokenValues(
            Map<String, ResourceTokenSettings> tokens,
            Optional<ProjectMetadata> project) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, ResourceTokenSettings> entry : tokens.entrySet()) {
            ResourceTokenSettings token = entry.getValue();
            String value = token.value()
                    .or(() -> token.env().map(ResourceFilteringProcessor::environmentToken))
                    .or(() -> token.project().map(projectField -> projectToken(project, projectField)))
                    .orElseThrow();
            values.put(entry.getKey(), value);
        }
        return values;
    }

    private static String environmentToken(String variable) {
        String value = System.getenv(variable);
        if (value == null) {
            throw new ResourceCopyException(
                    "Resource filtering token reads environment variable `"
                            + variable
                            + "`, but it is not set. Export the variable or change [resources.tokens].");
        }
        return value;
    }

    private static String projectToken(Optional<ProjectMetadata> project, String field) {
        ProjectMetadata metadata = project.orElseThrow(() -> new ResourceCopyException(
                "Resource filtering token references project field `"
                        + field
                        + "`, but project metadata is unavailable. Use zolt build/test/package so filtering can read [project]."));
        return switch (field) {
            case "name" -> metadata.name();
            case "version" -> metadata.version();
            case "group" -> metadata.group();
            case "java" -> metadata.java();
            case "main" -> metadata.main().orElse("");
            default -> throw new ResourceCopyException(
                    "Unsupported resource filtering project field `"
                            + field
                            + "`. Supported project fields are: name, version, group, java, main.");
        };
    }
}
