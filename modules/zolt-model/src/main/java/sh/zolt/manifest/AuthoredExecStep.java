package sh.zolt.manifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authored {@code kind = "exec"} generated step. */
public record AuthoredExecStep(
        GeneratedStepSettings settings,
        LocalId tool,
        Optional<JavaBinaryClassName> mainClass,
        List<String> args,
        List<ResourceGlob> inputs,
        ManifestRelativePath output,
        GeneratedOutputKind produces,
        Optional<ManifestRelativePath> into,
        Optional<GeneratedCachePolicy> cache,
        Optional<ManifestRelativePath> cwd,
        Map<EnvironmentVariableName, String> env,
        Map<EnvironmentVariableName, EnvironmentVariableName> secretEnv,
        List<EnvironmentVariableName> inheritEnv,
        Optional<Integer> timeoutSeconds) implements AuthoredGeneratedStep {
    private static final LocalId PROJECT = new LocalId("project");

    public AuthoredExecStep {
        Objects.requireNonNull(settings, "Exec step settings must not be null.");
        Objects.requireNonNull(tool, "Exec step tool reference must not be null.");
        mainClass = Objects.requireNonNull(mainClass, "Exec step main class must not be null.");
        validateProjectMainClass(tool, mainClass);
        args = immutableArguments(args);
        inputs = ManifestModelValues.sortedDistinctList(inputs, "Exec step inputs");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("An exec step requires at least one input.");
        }
        Objects.requireNonNull(output, "Exec step output must not be null.");
        Objects.requireNonNull(produces, "Exec step output kind must not be null.");
        into = Objects.requireNonNull(into, "Exec step resource destination must not be null.");
        if (into.isPresent() && !produces.producesResources()) {
            throw new IllegalArgumentException(
                    "Exec step `into` is valid only for resource-producing output kinds.");
        }
        cache = Objects.requireNonNull(cache, "Exec step cache policy must not be null.");
        cwd = Objects.requireNonNull(cwd, "Exec step working directory must not be null.");
        env = immutableEnvironment(env);
        secretEnv = immutableSecretEnvironment(secretEnv);
        inheritEnv = ManifestModelValues.sortedDistinctList(
                inheritEnv, "Exec step inherited environment names");
        validateEnvironment(env, secretEnv, inheritEnv);
        if (!secretEnv.isEmpty() && cache.orElse(GeneratedCachePolicy.CONTENT) != GeneratedCachePolicy.NONE) {
            throw new IllegalArgumentException(
                    "Exec steps with secretEnv require cache = `none`.");
        }
        timeoutSeconds = Objects.requireNonNull(
                timeoutSeconds, "Exec step timeout must not be null.");
        timeoutSeconds.ifPresent(value -> {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "Exec step timeoutSeconds must be positive.");
            }
        });
    }

    private static List<String> immutableArguments(List<String> values) {
        List<String> copy = ManifestModelValues.immutableList(values, "Exec step arguments");
        for (String value : copy) {
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Exec step arguments must not contain NUL.");
            }
        }
        return copy;
    }

    private static Map<EnvironmentVariableName, String> immutableEnvironment(
            Map<EnvironmentVariableName, String> values) {
        Map<EnvironmentVariableName, String> copy = ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                "Exec step environment name",
                "Exec step environment value");
        copy.values().forEach(value -> {
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Exec step environment values must not contain NUL.");
            }
        });
        return copy;
    }

    private static Map<EnvironmentVariableName, EnvironmentVariableName> immutableSecretEnvironment(
            Map<EnvironmentVariableName, EnvironmentVariableName> values) {
        return ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                "Exec step secret target environment name",
                "Exec step secret source environment name");
    }

    private static void validateEnvironment(
            Map<EnvironmentVariableName, String> env,
            Map<EnvironmentVariableName, EnvironmentVariableName> secretEnv,
            List<EnvironmentVariableName> inheritEnv) {
        ArrayList<EnvironmentVariableName> targets = new ArrayList<>(env.keySet());
        targets.addAll(secretEnv.keySet());
        targets.addAll(inheritEnv);
        if (new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException(
                    "Exec step environment targets must not be declared by more than one source.");
        }
        ManifestModelValues.rejectEnvironmentCaseCollisions(targets, "Exec step target");
        ArrayList<EnvironmentVariableName> allReferences = new ArrayList<>(targets);
        allReferences.addAll(secretEnv.values());
        ManifestModelValues.rejectEnvironmentCaseCollisions(allReferences, "Exec step");
    }

    private static void validateProjectMainClass(
            LocalId tool, Optional<JavaBinaryClassName> mainClass) {
        if (tool.equals(PROJECT) && mainClass.isEmpty()) {
            throw new IllegalArgumentException(
                    "An exec step using tool `project` requires mainClass.");
        }
        if (!tool.equals(PROJECT) && mainClass.isPresent()) {
            throw new IllegalArgumentException(
                    "Exec step mainClass is valid only with tool `project`.");
        }
    }
}
