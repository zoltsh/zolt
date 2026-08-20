package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredRepositoryControl;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;

/** Decodes the optional dependency-repository universe and exact lookup controls. */
final class ManifestDependencyRepositoriesDecoder {
    Optional<AuthoredDependencyRepositories> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.REPOSITORIES).map(section -> {
            Optional<AuthoredRepositoryControl> control = decodeControl(index, section);
            Map<LocalId, DependencyRepository> named = decodeNamed(index);
            return ManifestSemanticDiagnostics.construct(
                    section,
                    () -> new AuthoredDependencyRepositories(control, named));
        });
    }

    private static Optional<AuthoredRepositoryControl> decodeControl(
            ManifestDecodeIndex index,
            ValidatedManifestSection section) {
        Optional<CentralRepositoryControl> central = index
                .field(FinalManifestSharedFields.REPOSITORIES_CENTRAL)
                .map(ManifestDependencyRepositoriesDecoder::decodeCentral);
        Optional<List<LocalId>> order = index
                .field(FinalManifestSharedFields.REPOSITORIES_ORDER)
                .map(ManifestDependencyRepositoriesDecoder::decodeOrder);
        if (central.isEmpty() && order.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                section, () -> new AuthoredRepositoryControl(central, order)));
    }

    private static CentralRepositoryControl decodeCentral(ValidatedManifestField field) {
        if (ManifestTomlValues.isBoolean(field)) {
            return ManifestTomlValues.booleanValue(field)
                    ? new CentralRepositoryControl.Enabled()
                    : new CentralRepositoryControl.Disabled();
        }
        if (ManifestTomlValues.isString(field)) {
            RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                    field, () -> new RepositoryUrl(ManifestTomlValues.string(field)));
            return new CentralRepositoryControl.Replacement(url, Optional.empty());
        }
        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.CENTRAL_URL,
                () -> new RepositoryUrl(
                        table.requiredString(FinalManifestObjectShapes.CENTRAL_URL)));
        Optional<LocalId> credentials = table
                .optionalString(FinalManifestObjectShapes.CENTRAL_CREDENTIALS)
                .map(value -> ManifestSemanticDiagnostics.construct(
                        table,
                        FinalManifestObjectShapes.CENTRAL_CREDENTIALS,
                        () -> new LocalId(value)));
        return new CentralRepositoryControl.Replacement(url, credentials);
    }

    private static List<LocalId> decodeOrder(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> ManifestTomlValues.strings(field).stream()
                        .map(LocalId::new)
                        .toList());
    }

    private static Map<LocalId, DependencyRepository> decodeNamed(
            ManifestDecodeIndex index) {
        LinkedHashMap<LocalId, DependencyRepository> named = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry :
                index.sectionEntries(FinalManifestPaths.REPOSITORY)) {
            LocalId id = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new LocalId(entry.key()));
            DependencyRepository repository = decodeNamed(index, entry);
            if (named.put(id, repository) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate repository `" + id + "`.");
            }
        }
        return named;
    }

    private static DependencyRepository decodeNamed(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        ValidatedManifestField urlField = ManifestSemanticDiagnostics.requiredField(
                index, entry, FinalManifestSharedFields.REPOSITORY_URL);
        RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                urlField, () -> new RepositoryUrl(ManifestTomlValues.string(urlField)));
        Optional<LocalId> credentials = index
                .field(entry, FinalManifestSharedFields.REPOSITORY_CREDENTIALS)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field, () -> new LocalId(ManifestTomlValues.string(field))));
        return ManifestSemanticDiagnostics.construct(
                entry.section(), () -> new DependencyRepository(url, credentials));
    }
}
