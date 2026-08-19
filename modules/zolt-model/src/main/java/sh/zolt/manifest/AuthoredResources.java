package sh.zolt.manifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authored resource roots, filtering, and typed token sources. */
public record AuthoredResources(
        List<ManifestRelativePath> main,
        List<ManifestRelativePath> test,
        Optional<Filter> filter,
        Map<LocalId, Token> tokens) {
    public AuthoredResources {
        main = ManifestModelValues.sortedDistinctList(main, "Main resource roots");
        test = ManifestModelValues.sortedDistinctList(test, "Test resource roots");
        filter = Objects.requireNonNull(filter, "Authored resource filter must not be null.");
        tokens = immutableTokens(tokens);
        rejectEnvironmentCaseCollisions(tokens);
    }

    public static AuthoredResources empty() {
        return new AuthoredResources(List.of(), List.of(), Optional.empty(), Map.of());
    }

    /** Presence enables resource filtering; include globs are always explicit and nonempty. */
    public record Filter(
            Optional<List<Target>> targets,
            List<ResourceGlob> include,
            Optional<MissingTokenPolicy> missing) {
        public Filter {
            Objects.requireNonNull(targets, "Authored resource filter targets must not be null.");
            if (targets.isPresent()) {
                List<Target> copied = ManifestModelValues.immutableList(
                        targets.orElseThrow(), "Resource filter targets");
                if (copied.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Authored resource filter targets must be omitted or nonempty.");
                }
                ManifestModelValues.rejectDuplicates(copied, "Resource filter targets");
                copied = copied.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
                targets = Optional.of(copied);
            }
            include = ManifestModelValues.sortedDistinctList(
                    include, "Resource filter include globs");
            if (include.isEmpty()) {
                throw new IllegalArgumentException(
                        "Resource filtering requires at least one include glob.");
            }
            missing = Objects.requireNonNull(
                    missing, "Authored missing-resource-token policy must not be null.");
        }
    }

    public enum Target {
        MAIN("main"),
        TEST("test");

        private final String configValue;

        Target(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    public enum MissingTokenPolicy {
        FAIL("fail"),
        KEEP("keep");

        private final String configValue;

        MissingTokenPolicy(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    /** Exactly one explicit source for a resource-filter token. */
    public sealed interface Token permits Token.Literal, Token.Environment, Token.Project {
        record Literal(String value) implements Token {
            public Literal {
                Objects.requireNonNull(value, "Literal resource token value must not be null.");
                if (value.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException(
                            "Literal resource token value must not contain NUL.");
                }
            }
        }

        record Environment(EnvironmentVariableName env) implements Token {
            public Environment {
                Objects.requireNonNull(env, "Resource token environment name must not be null.");
            }
        }

        record Project(ProjectField field) implements Token {
            public Project {
                Objects.requireNonNull(field, "Resource token project field must not be null.");
            }
        }
    }

    public enum ProjectField {
        NAME("name"),
        VERSION("version"),
        GROUP("group"),
        JAVA("java"),
        MAIN("main");

        private final String configValue;

        ProjectField(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    private static Map<LocalId, Token> immutableTokens(Map<LocalId, Token> values) {
        return ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                "Resource token ID",
                "Resource token source");
    }

    private static void rejectEnvironmentCaseCollisions(Map<LocalId, Token> tokens) {
        ArrayList<EnvironmentVariableName> names = new ArrayList<>();
        for (Token token : tokens.values()) {
            if (token instanceof Token.Environment environment) {
                names.add(environment.env());
            }
        }
        ManifestModelValues.rejectEnvironmentCaseCollisions(names, "Resource token");
    }
}
