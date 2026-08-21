package sh.zolt.manifest.effective;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredPackage;

/** Resolves and validates graph facts that require the complete effective workspace. */
final class EffectiveWorkspaceGraphComposer {
    private EffectiveWorkspaceGraphComposer() {}

    static EffectiveWorkspaceGraph compose(
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        Map<DependencyCoordinate, WorkspaceMemberPath> memberByIdentity =
                indexIdentities(members);
        ArrayList<EffectiveWorkspaceDependencyEdge> workspaceDependencies = new ArrayList<>();
        ArrayList<EffectiveManagedDependencyRequest> managedDependencies = new ArrayList<>();
        for (Map.Entry<WorkspaceMemberPath, EffectiveManifest> entry : members.entrySet()) {
            resolveDependencies(
                    entry.getKey(),
                    entry.getValue(),
                    members,
                    memberByIdentity,
                    workspaceDependencies,
                    managedDependencies);
        }
        rejectCycles(members.keySet(), workspaceDependencies);
        return new EffectiveWorkspaceGraph(
                workspaceDependencies,
                managedDependencies,
                resolveBomMembers(members));
    }

    private static Map<DependencyCoordinate, WorkspaceMemberPath> indexIdentities(
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        LinkedHashMap<DependencyCoordinate, WorkspaceMemberPath> memberByIdentity =
                new LinkedHashMap<>();
        members.forEach((path, manifest) -> {
            EffectiveProjectIdentity identity = manifest.project().identity();
            DependencyCoordinate coordinate = new DependencyCoordinate(
                    identity.group().value().value() + ":"
                            + identity.name().value().value());
            WorkspaceMemberPath duplicate = memberByIdentity.putIfAbsent(coordinate, path);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "Workspace members `" + duplicate + "` and `" + path
                                + "` have duplicate effective project identity `"
                                + coordinate + "`.");
            }
        });
        return Map.copyOf(memberByIdentity);
    }

    private static void resolveDependencies(
            WorkspaceMemberPath owner,
            EffectiveManifest member,
            Map<WorkspaceMemberPath, EffectiveManifest> members,
            Map<DependencyCoordinate, WorkspaceMemberPath> memberByIdentity,
            List<EffectiveWorkspaceDependencyEdge> workspaceDependencies,
            List<EffectiveManagedDependencyRequest> managedDependencies) {
        AuthoredDependencies dependencies = member.project()
                .local()
                .dependencies()
                .orElseGet(AuthoredDependencies::empty);
        for (AuthoredDependency declaration : dependencies.declarations()) {
            switch (declaration.selector()) {
                case DependencySelector.Workspace ignored -> workspaceDependencies.add(
                        resolveWorkspaceDependency(
                                owner, declaration, members, memberByIdentity));
                case DependencySelector.Managed ignored -> {
                    requirePlatformImport(owner, member, declaration);
                    managedDependencies.add(
                            new EffectiveManagedDependencyRequest(owner, declaration));
                }
                case DependencySelector.FixedVersion ignored -> {
                    // External fixed requests need no workspace graph resolution.
                }
                case DependencySelector.VersionReference ignored -> {
                    // Alias integrity was validated while composing the effective member.
                }
            }
        }
    }

    private static EffectiveWorkspaceDependencyEdge resolveWorkspaceDependency(
            WorkspaceMemberPath owner,
            AuthoredDependency declaration,
            Map<WorkspaceMemberPath, EffectiveManifest> members,
            Map<DependencyCoordinate, WorkspaceMemberPath> memberByIdentity) {
        WorkspaceMemberPath provider = memberByIdentity.get(declaration.coordinate());
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Workspace dependency `" + declaration.coordinate()
                            + "` in member `" + owner
                            + "` has no member with matching effective project identity.");
        }
        if (provider.equals(owner)) {
            throw new IllegalArgumentException(
                    "Workspace member `" + owner + "` cannot depend on itself through `"
                            + declaration.coordinate() + "`.");
        }
        requireConsumable(
                members.get(provider),
                "Workspace dependency `" + declaration.coordinate()
                        + "` in member `" + owner + "` targets member `" + provider + "`");
        return new EffectiveWorkspaceDependencyEdge(owner, provider, declaration);
    }

    private static void requirePlatformImport(
            WorkspaceMemberPath owner,
            EffectiveManifest member,
            AuthoredDependency dependency) {
        if (member.project().shared().platforms().isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed dependency `" + dependency.coordinate()
                            + "` in member `" + owner
                            + "` has no effective [platforms] import.");
        }
    }

    private static Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> resolveBomMembers(
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        LinkedHashMap<WorkspaceMemberPath, List<WorkspaceMemberPath>> resolved =
                new LinkedHashMap<>();
        members.forEach((owner, manifest) -> manifest.project()
                .local()
                .packaging()
                .bom()
                .flatMap(AuthoredBom::members)
                .ifPresent(selection -> resolved.put(
                        owner, resolveBomSelection(owner, selection, members))));
        return Map.copyOf(resolved);
    }

    private static List<WorkspaceMemberPath> resolveBomSelection(
            WorkspaceMemberPath owner,
            AuthoredBom.Members membersSelection,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        return switch (membersSelection.selection()) {
            case AuthoredBom.AllMembers ignored -> allBomMembers(
                    owner, membersSelection.exclude(), members);
            case AuthoredBom.ExplicitMembers explicit -> explicitBomMembers(
                    owner, explicit.paths(), members);
        };
    }

    private static List<WorkspaceMemberPath> allBomMembers(
            WorkspaceMemberPath owner,
            List<WorkspaceMemberPath> exclusions,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        requireFinalMembers(exclusions, members, "BOM exclusion");
        Set<WorkspaceMemberPath> excluded = Set.copyOf(exclusions);
        return members.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(owner))
                .filter(entry -> !excluded.contains(entry.getKey()))
                .filter(entry -> consumable(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<WorkspaceMemberPath> explicitBomMembers(
            WorkspaceMemberPath owner,
            List<WorkspaceMemberPath> selected,
            Map<WorkspaceMemberPath, EffectiveManifest> members) {
        requireFinalMembers(selected, members, "Explicit BOM member");
        for (WorkspaceMemberPath path : selected) {
            if (path.equals(owner)) {
                throw new IllegalArgumentException(
                        "BOM member `" + owner + "` cannot include itself.");
            }
            requireConsumable(
                    members.get(path),
                    "BOM member `" + owner + "` explicitly selects member `" + path + "`");
        }
        return selected;
    }

    private static void requireFinalMembers(
            List<WorkspaceMemberPath> selected,
            Map<WorkspaceMemberPath, EffectiveManifest> members,
            String label) {
        for (WorkspaceMemberPath path : selected) {
            if (!members.containsKey(path)) {
                throw new IllegalArgumentException(
                        label + " `" + path + "` is not in the final workspace member set.");
            }
        }
    }

    private static void requireConsumable(EffectiveManifest member, String subject) {
        if (!consumable(member)) {
            throw new IllegalArgumentException(
                    subject + ", whose package mode is `" + packageMode(member)
                            + "`, which is not a consumable library JAR.");
        }
    }

    private static boolean consumable(EffectiveManifest member) {
        if (member.project().local().packaging().bom().isPresent()) {
            return false;
        }
        return member.project()
                        .local()
                        .packaging()
                        .packageSettings()
                        .flatMap(AuthoredPackage::mode)
                        .orElse(AuthoredPackage.Mode.JAR)
                == AuthoredPackage.Mode.JAR;
    }

    private static String packageMode(EffectiveManifest member) {
        if (member.project().local().packaging().bom().isPresent()) {
            return "bom";
        }
        return member.project()
                .local()
                .packaging()
                .packageSettings()
                .flatMap(AuthoredPackage::mode)
                .orElse(AuthoredPackage.Mode.JAR)
                .configValue();
    }

    private static void rejectCycles(
            Set<WorkspaceMemberPath> members,
            List<EffectiveWorkspaceDependencyEdge> edges) {
        Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> adjacency = adjacency(members, edges);
        Map<WorkspaceMemberPath, VisitState> states = new HashMap<>();
        ArrayList<WorkspaceMemberPath> stack = new ArrayList<>();
        for (WorkspaceMemberPath member : members) {
            if (states.getOrDefault(member, VisitState.NEW) == VisitState.NEW) {
                visit(member, adjacency, states, stack);
            }
        }
    }

    private static Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> adjacency(
            Set<WorkspaceMemberPath> members,
            List<EffectiveWorkspaceDependencyEdge> edges) {
        LinkedHashMap<WorkspaceMemberPath, LinkedHashSet<WorkspaceMemberPath>> values =
                new LinkedHashMap<>();
        members.forEach(member -> values.put(member, new LinkedHashSet<>()));
        edges.stream().sorted().forEach(edge ->
                values.get(edge.consumer()).add(edge.provider()));
        LinkedHashMap<WorkspaceMemberPath, List<WorkspaceMemberPath>> result =
                new LinkedHashMap<>();
        values.forEach((member, targets) -> result.put(
                member, targets.stream().sorted().toList()));
        return Map.copyOf(result);
    }

    private static void visit(
            WorkspaceMemberPath member,
            Map<WorkspaceMemberPath, List<WorkspaceMemberPath>> adjacency,
            Map<WorkspaceMemberPath, VisitState> states,
            List<WorkspaceMemberPath> stack) {
        states.put(member, VisitState.ACTIVE);
        stack.add(member);
        for (WorkspaceMemberPath target : adjacency.get(member)) {
            VisitState state = states.getOrDefault(target, VisitState.NEW);
            if (state == VisitState.ACTIVE) {
                throw cycle(stack, target);
            }
            if (state == VisitState.NEW) {
                visit(target, adjacency, states, stack);
            }
        }
        stack.remove(stack.size() - 1);
        states.put(member, VisitState.COMPLETE);
    }

    private static IllegalArgumentException cycle(
            List<WorkspaceMemberPath> stack,
            WorkspaceMemberPath target) {
        int start = stack.indexOf(target);
        ArrayList<WorkspaceMemberPath> cycle = new ArrayList<>(stack.subList(start, stack.size()));
        cycle.add(target);
        return new IllegalArgumentException(
                "Workspace dependency cycle detected: " + cycle.stream()
                        .map(WorkspaceMemberPath::value)
                        .collect(Collectors.joining(" -> ")) + ".");
    }

    private enum VisitState {
        NEW,
        ACTIVE,
        COMPLETE
    }
}
