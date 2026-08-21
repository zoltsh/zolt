package sh.zolt.quality.execution;

import static sh.zolt.quality.QualityCheckService.EXECUTION_CONTEXT;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceTokenSettings;
import sh.zolt.publish.PublishRepositorySettings;
import sh.zolt.publish.PublishSettings;
import sh.zolt.publish.ManifestPublishSettingsLoader;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

final class CredentialQualityCheck {
    private final ManifestPublishSettingsLoader publishSettingsLoader;
    private final CredentialEnvironmentValidator credentialEnvironmentValidator;

    CredentialQualityCheck(ManifestPublishSettingsLoader publishSettingsLoader, Function<String, String> environment) {
        this.publishSettingsLoader = publishSettingsLoader;
        this.credentialEnvironmentValidator = new CredentialEnvironmentValidator(environment);
    }

    List<QualityCheckResult> checkRepositoryCredentials(
            Optional<String> member,
            ProjectConfig config,
            QualityCheckContext context) {
        if (context != QualityCheckContext.CI) {
            return List.of();
        }

        List<RepositorySettings> repositories = config.repositorySettings().values().stream()
                .sorted(Comparator.comparing(RepositorySettings::id))
                .toList();
        List<QualityCheckResult> results = new ArrayList<>();
        int credentialedRepositories = 0;
        for (RepositorySettings repository : repositories) {
            Optional<QualityCheckResult> embeddedCredentials = embeddedRepositoryCredentials(member, repository);
            if (embeddedCredentials.isPresent()) {
                results.add(embeddedCredentials.orElseThrow());
                continue;
            }
            Optional<String> credentialId = repository.credentials();
            if (credentialId.isEmpty()) {
                continue;
            }
            credentialedRepositories++;
            RepositoryCredentialSettings credential = config.repositoryCredentials().get(credentialId.orElseThrow());
            if (credential == null) {
                results.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        "[repositoryCredentials." + credentialId.orElseThrow() + "]",
                        "Repository `" + repository.id() + "` references missing credential metadata.",
                        "Define [repositoryCredentials." + credentialId.orElseThrow() + "] with environment variable names, not secret values."));
                continue;
            }

            CredentialEnvironmentCheck environmentCheck = credentialEnvironmentValidator.check(credential);
            List<String> missing = environmentCheck.missing();
            if (!missing.isEmpty()) {
                results.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        "[repositoryCredentials." + credential.id() + "]",
                        "CI context requires environment variable"
                                + (missing.size() == 1 ? " " : "s ")
                                + String.join(", ", missing)
                                + " for repository `"
                                + repository.id()
                                + "` credentials `"
                                + credential.id()
                                + "` before resolve/build work starts.",
                        "Set the named CI secret"
                                + (missing.size() == 1 ? "" : "s")
                                + " and rerun `zolt check --context ci`. Secret values are never printed."));
                continue;
            }

            List<String> placeholders = environmentCheck.placeholders();
            if (!placeholders.isEmpty()) {
                results.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        "[repositoryCredentials." + credential.id() + "]",
                        "CI context rejects placeholder credential value"
                                + (placeholders.size() == 1 ? " " : "s ")
                                + "for environment variable"
                                + (placeholders.size() == 1 ? " " : "s ")
                                + String.join(", ", placeholders)
                                + " on repository `"
                                + repository.id()
                                + "`.",
                        "Replace placeholder credentials with real CI secrets. Zolt reports only variable names, never secret values."));
            }
        }

        if (results.isEmpty() && credentialedRepositories > 0) {
            results.add(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    member,
                    "repository-credentials",
                    "CI credential preflight passed for "
                            + credentialedRepositories
                            + " credentialed "
                            + (credentialedRepositories == 1 ? "repository." : "repositories.")));
        }
        return List.copyOf(results);
    }

    List<QualityCheckResult> checkPublishCredentials(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            QualityCheckContext context) {
        if (context != QualityCheckContext.CI) {
            return List.of();
        }
        PublishSettings publish = publishSettingsLoader.read(root.resolve("zolt.toml"));
        if (!publish.configured()) {
            return List.of();
        }

        List<PublishRepositorySettings> repositories = publish.repositories().values().stream()
                .sorted(Comparator.comparing(PublishRepositorySettings::id))
                .toList();
        List<QualityCheckResult> results = new ArrayList<>();
        int credentialedRepositories = 0;
        for (PublishRepositorySettings repository : repositories) {
            Optional<QualityCheckResult> embeddedCredentials = embeddedPublishRepositoryCredentials(member, repository);
            if (embeddedCredentials.isPresent()) {
                results.add(embeddedCredentials.orElseThrow());
                continue;
            }
            Optional<String> credentialId = repository.credentials();
            if (credentialId.isEmpty()) {
                continue;
            }
            credentialedRepositories++;
            RepositoryCredentialSettings credential = config.repositoryCredentials().get(credentialId.orElseThrow());
            if (credential == null) {
                results.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        "[repositoryCredentials." + credentialId.orElseThrow() + "]",
                        "Publish repository `" + repository.id() + "` references missing credential metadata.",
                        "Define [repositoryCredentials." + credentialId.orElseThrow() + "] with environment variable names, not secret values."));
                continue;
            }

            CredentialEnvironmentCheck environmentCheck = credentialEnvironmentValidator.check(credential);
            List<String> missing = environmentCheck.missing();
            if (!missing.isEmpty()) {
                results.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        "[repositoryCredentials." + credential.id() + "]",
                        "CI context requires environment variable"
                                + (missing.size() == 1 ? " " : "s ")
                                + String.join(", ", missing)
                                + " for publish repository `"
                                + repository.id()
                                + "` credentials `"
                                + credential.id()
                                + "` before publish work starts.",
                        "Set the named CI secret"
                                + (missing.size() == 1 ? "" : "s")
                                + " and rerun `zolt check --context ci`. Secret values are never printed."));
                continue;
            }

            List<String> placeholders = environmentCheck.placeholders();
            if (!placeholders.isEmpty()) {
                results.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        "[repositoryCredentials." + credential.id() + "]",
                        "CI context rejects placeholder credential value"
                                + (placeholders.size() == 1 ? " " : "s ")
                                + "for environment variable"
                                + (placeholders.size() == 1 ? " " : "s ")
                                + String.join(", ", placeholders)
                                + " on publish repository `"
                                + repository.id()
                                + "`.",
                        "Replace placeholder credentials with real CI secrets. Zolt reports only variable names, never secret values."));
            }
        }

        if (results.isEmpty() && credentialedRepositories > 0) {
            results.add(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    member,
                    "publish-credentials",
                    "CI publish credential preflight passed for "
                            + credentialedRepositories
                            + " credentialed publish "
                            + (credentialedRepositories == 1 ? "repository." : "repositories.")));
        }
        return List.copyOf(results);
    }

    List<QualityCheckResult> checkResourceTokens(
            Optional<String> member,
            ProjectConfig config,
            QualityCheckContext context) {
        if (context != QualityCheckContext.CI) {
            return List.of();
        }
        ResourceFilteringSettings filtering = config.build().resourceFiltering();
        if ((!filtering.enabled() && !filtering.testEnabled()) || filtering.tokens().isEmpty()) {
            return List.of();
        }
        List<QualityCheckResult> failures = new ArrayList<>();
        int envTokens = 0;
        int literalTokens = 0;
        int projectTokens = 0;
        for (Map.Entry<String, ResourceTokenSettings> entry : filtering.tokens().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            ResourceTokenSettings token = entry.getValue();
            if (token.env().isPresent()) {
                envTokens++;
                String env = token.env().orElseThrow();
                if (credentialEnvironmentValidator.isMissingEnvironmentValue(env)) {
                    failures.add(QualityCheckResult.failed(
                            EXECUTION_CONTEXT,
                            member,
                            "[resources.tokens." + entry.getKey() + "]",
                            "CI context requires environment variable "
                                    + env
                                    + " for resource token `"
                                    + entry.getKey()
                                    + "` before resource copying.",
                            "Set the named CI variable or change [resources.tokens]."
                                    + entry.getKey()
                                    + " to an explicit non-secret value/project source. Values are never printed."));
                }
            } else if (token.project().isPresent()) {
                projectTokens++;
            } else {
                literalTokens++;
            }
        }
        if (!failures.isEmpty()) {
            return List.copyOf(failures);
        }
        int total = envTokens + literalTokens + projectTokens;
        return List.of(QualityCheckResult.passed(
                EXECUTION_CONTEXT,
                member,
                "resource-token-inputs",
                "CI resource token preflight passed for "
                        + total
                        + " "
                        + (total == 1 ? "token" : "tokens")
                        + ": env="
                        + envTokens
                        + ", project="
                        + projectTokens
                        + ", literal="
                        + literalTokens
                        + "."));
    }

    private Optional<QualityCheckResult> embeddedRepositoryCredentials(
            Optional<String> member,
            RepositorySettings repository) {
        try {
            URI uri = new URI(repository.url());
            if (uri.getUserInfo() == null || uri.getUserInfo().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "[repositories." + repository.id() + "]",
                    "CI context rejects embedded credentials in repository `" + repository.id() + "` URL.",
                    "Move credentials to [repositoryCredentials] environment references. Do not commit username, password, or token values in repository URLs."));
        } catch (URISyntaxException exception) {
            return Optional.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "[repositories." + repository.id() + "]",
                    "Repository `" + repository.id() + "` URL is not a valid URI.",
                    "Edit [repositories." + repository.id() + "] to use a Maven-compatible HTTPS URL without embedded credentials."));
        }
    }

    private Optional<QualityCheckResult> embeddedPublishRepositoryCredentials(
            Optional<String> member,
            PublishRepositorySettings repository) {
        try {
            URI uri = new URI(repository.url());
            if (uri.getUserInfo() == null || uri.getUserInfo().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "[publish.repositories." + repository.id() + "]",
                    "CI context rejects embedded credentials in publish repository `" + repository.id() + "` URL.",
                    "Move publish credentials to [repositoryCredentials] environment references. Do not commit username, password, or token values in publish repository URLs."));
        } catch (URISyntaxException exception) {
            return Optional.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "[publish.repositories." + repository.id() + "]",
                    "Publish repository `" + repository.id() + "` URL is not a valid URI.",
                    "Edit [publish.repositories." + repository.id() + "] to use a Maven-compatible HTTPS URL without embedded credentials."));
        }
    }

}
