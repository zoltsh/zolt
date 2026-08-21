package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.authored.AuthoredProjectDeveloper;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredProjectScm;
import sh.zolt.manifest.effective.EffectiveProjectIdentity;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/**
 * Projects effective project identity and {@code [project]} publication metadata onto the legacy
 * {@link ProjectMetadata} and {@link PublicationMetadata} records.
 *
 * <p>The final language moved publication metadata out of {@code [package.metadata]} and into
 * {@code [project]}, {@code [project.scm]}, and {@code [project.developers.<id>]} (design §14.4), so
 * the adapter reads it from the project domain. Two legacy fields have no final source: the POM
 * display {@code name} and the flat {@code developers} name array, which the structured
 * {@code [project.developers.<id>]} tables replace.
 */
final class ProjectConfigIdentity {
    private ProjectConfigIdentity() {
    }

    static ProjectMetadata project(
            EffectiveProjectIdentity identity,
            AuthoredProjectMetadata metadata) {
        return new ProjectMetadata(
                identity.name().value().value(),
                identity.version().value().value(),
                identity.group().value().value(),
                javaRelease(identity),
                metadata.main().map(value -> value.value()));
    }

    static PublicationMetadata publication(
            EffectiveProjectIdentity identity,
            AuthoredProjectMetadata metadata) {
        Optional<AuthoredProjectScm> scm = metadata.scm();
        return new PublicationMetadata(
                "",
                metadata.description().orElse(""),
                metadata.url().orElse(""),
                licenseName(identity),
                licenseUrl(identity),
                List.of(),
                developers(metadata),
                scm.flatMap(AuthoredProjectScm::url).orElse(""),
                scm.flatMap(AuthoredProjectScm::connection).orElse(""),
                scm.flatMap(AuthoredProjectScm::developerConnection).orElse(""),
                scm.flatMap(AuthoredProjectScm::tag).orElse(""),
                metadata.issues().orElse(""));
    }

    /**
     * The legacy {@code [project].java} string. A BOM has no effective Java release because design
     * §12.6 forbids one, so the adapter reports it as absent rather than inventing a release.
     */
    private static String javaRelease(EffectiveProjectIdentity identity) {
        return identity.javaRelease()
                .map(EffectiveValue::value)
                .map(JavaFeatureRelease::value)
                .map(String::valueOf)
                .orElse("");
    }

    private static String licenseName(EffectiveProjectIdentity identity) {
        return identity.license()
                .map(EffectiveValue::value)
                .map(license -> switch (license) {
                    case ProjectLicense.Identifier identifier -> identifier.id();
                    case ProjectLicense.Metadata authored ->
                            authored.name().or(authored::id).orElse("");
                })
                .orElse("");
    }

    private static String licenseUrl(EffectiveProjectIdentity identity) {
        return identity.license()
                .map(EffectiveValue::value)
                .map(license -> switch (license) {
                    case ProjectLicense.Identifier ignored -> "";
                    case ProjectLicense.Metadata authored -> authored.url().orElse("");
                })
                .orElse("");
    }

    private static List<DeveloperEntry> developers(AuthoredProjectMetadata metadata) {
        List<DeveloperEntry> entries = new ArrayList<>();
        metadata.developers().forEach((id, developer) ->
                entries.add(developer(id.value(), developer)));
        return List.copyOf(entries);
    }

    private static DeveloperEntry developer(String id, AuthoredProjectDeveloper developer) {
        return new DeveloperEntry(
                id,
                developer.name().orElse(""),
                developer.email().orElse(""),
                developer.organization().orElse(""),
                developer.url().orElse(""));
    }
}
