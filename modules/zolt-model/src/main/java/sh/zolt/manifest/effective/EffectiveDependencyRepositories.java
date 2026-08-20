package sh.zolt.manifest.effective;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;

/** Complete effective dependency-repository universe and its exact lookup order. */
public record EffectiveDependencyRepositories(
        EffectiveValue<EffectiveCentralRepository> central,
        Map<LocalId, EffectiveValue<DependencyRepository>> named,
        EffectiveValue<List<LocalId>> lookupOrder) {
    private static final LocalId CENTRAL = new LocalId("central");
    private static final LocalId ORDER = new LocalId("order");

    public EffectiveDependencyRepositories {
        central = Objects.requireNonNull(central, "Effective Central control must not be null.");
        validateBuiltInCentral(central);
        named = ManifestModelValues.immutableSortedMap(
                named,
                LocalId::compareTo,
                "Effective repository ID",
                "Effective repository");
        rejectReservedNamedRepositories(named);
        rejectBuiltInNamedRepositories(named);
        lookupOrder = Objects.requireNonNull(
                        lookupOrder, "Effective repository lookup order must not be null.")
                .map(values -> ManifestModelValues.immutableList(
                        values, "Effective repository lookup order"));
        validateLookupOrder(central.value(), named, lookupOrder.value());
        validateBuiltInLookupOrder(central.value(), named, lookupOrder);
    }

    private static void validateBuiltInCentral(
            EffectiveValue<EffectiveCentralRepository> central) {
        if (central.origin() != ValueOrigin.BUILT_IN) {
            return;
        }
        DependencyRepository canonical = DependencyRepository.unauthenticated(
                AuthoredDependencyRepositories.MAVEN_CENTRAL_URL);
        if (!central.value().equals(EffectiveCentralRepository.enabled(canonical))) {
            throw new IllegalArgumentException(
                    "Built-in Central must be the enabled unauthenticated canonical Maven Central repository.");
        }
    }

    private static void rejectReservedNamedRepositories(
            Map<LocalId, EffectiveValue<DependencyRepository>> repositories) {
        if (repositories.containsKey(CENTRAL) || repositories.containsKey(ORDER)) {
            throw new IllegalArgumentException(
                    "`central` and `order` are reserved and cannot be named repositories.");
        }
    }

    private static void rejectBuiltInNamedRepositories(
            Map<LocalId, EffectiveValue<DependencyRepository>> repositories) {
        for (Map.Entry<LocalId, EffectiveValue<DependencyRepository>> entry
                : repositories.entrySet()) {
            if (entry.getValue().origin() == ValueOrigin.BUILT_IN) {
                throw new IllegalArgumentException(
                        "Effective repository `" + entry.getKey()
                                + "` must be authored or inherited.");
            }
        }
    }

    private static void validateLookupOrder(
            EffectiveCentralRepository central,
            Map<LocalId, EffectiveValue<DependencyRepository>> named,
            List<LocalId> order) {
        Set<LocalId> expected = new HashSet<>(named.keySet());
        if (central.enabled()) {
            expected.add(CENTRAL);
        }
        Set<LocalId> actual = new HashSet<>(order);
        if (actual.size() != order.size()) {
            throw new IllegalArgumentException(
                    "Effective repository lookup order must not contain duplicate IDs.");
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "Effective repository lookup order must list every enabled repository ID exactly once.");
        }
    }

    private static void validateBuiltInLookupOrder(
            EffectiveCentralRepository central,
            Map<LocalId, EffectiveValue<DependencyRepository>> named,
            EffectiveValue<List<LocalId>> order) {
        if (order.origin() != ValueOrigin.BUILT_IN) {
            return;
        }
        ArrayList<LocalId> expected = new ArrayList<>(named.keySet());
        if (central.enabled()) {
            expected.add(CENTRAL);
        }
        if (!order.value().equals(expected)) {
            throw new IllegalArgumentException(
                    "Built-in repository lookup order must use sorted custom IDs followed by Central.");
        }
    }
}
