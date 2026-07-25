package sh.zolt.sbom;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class WorkspaceSbomExternalContexts {
    private final Map<ContextKey, String> refs;
    private final Map<String, Set<String>> refsByPackage;

    private WorkspaceSbomExternalContexts(
            Map<ContextKey, String> refs,
            Map<String, Set<String>> refsByPackage) {
        this.refs = Map.copyOf(refs);
        this.refsByPackage = Map.copyOf(refsByPackage);
    }

    static WorkspaceSbomExternalContexts create(
            ZoltLockfile lockfile,
            SbomScopeSelection selection,
            LockMemberGraphIndex memberGraphs) {
        Map<ContextKey, String> refs = new LinkedHashMap<>();
        Map<String, Set<String>> refsByPackage = new LinkedHashMap<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (!externalIncluded(lockPackage, selection)) {
                continue;
            }
            addPackageContexts(
                    lockPackage,
                    memberGraphs,
                    refs,
                    refsByPackage);
        }
        return new WorkspaceSbomExternalContexts(refs, refsByPackage);
    }

    private static boolean externalIncluded(
            LockPackage lockPackage,
            SbomScopeSelection selection) {
        return lockPackage.workspace().isEmpty()
                && lockPackage.jar().isPresent()
                && selection.includes(
                        SbomScopeGroup.of(lockPackage.scope()));
    }

    private static void addPackageContexts(
            LockPackage lockPackage,
            LockMemberGraphIndex memberGraphs,
            Map<ContextKey, String> refs,
            Map<String, Set<String>> refsByPackage) {
        String packageRef = refOf(lockPackage);
        String purl = LockArtifacts.purl(lockPackage);
        List<String> members = lockPackage.members().isEmpty()
                ? List.of("")
                : lockPackage.members().stream().sorted().toList();
        Map<List<String>, List<String>> membersByGraph =
                groupMembersByGraph(lockPackage, members, memberGraphs);
        boolean qualified = membersByGraph.size() > 1;
        for (List<String> graphMembers : membersByGraph.values()) {
            String contextRef = qualified
                    ? qualifiedRef(purl, graphMembers.getFirst())
                    : purl;
            for (String member : graphMembers) {
                refs.put(
                        new ContextKey(packageRef, member),
                        contextRef);
            }
            refsByPackage
                    .computeIfAbsent(
                            packageRef,
                            ignored -> new LinkedHashSet<>())
                    .add(contextRef);
        }
    }

    private static Map<List<String>, List<String>> groupMembersByGraph(
            LockPackage lockPackage,
            List<String> members,
            LockMemberGraphIndex memberGraphs) {
        Map<List<String>, List<String>> membersByGraph =
                new LinkedHashMap<>();
        for (String member : members) {
            List<String> dependencies = member.isEmpty()
                    ? lockPackage.dependencies()
                    : memberGraphs.dependenciesFor(member, lockPackage);
            membersByGraph
                    .computeIfAbsent(
                            List.copyOf(dependencies),
                            ignored -> new ArrayList<>())
                    .add(member);
        }
        return membersByGraph;
    }

    private static String qualifiedRef(String purl, String member) {
        return purl
                + "#zolt-context="
                + URLEncoder.encode(member, StandardCharsets.UTF_8);
    }

    Set<String> refs(LockPackage lockPackage) {
        return refsByPackage.getOrDefault(
                refOf(lockPackage), Set.of());
    }

    String ref(LockPackage lockPackage, String member) {
        String packageRef = refOf(lockPackage);
        String exact = refs.get(new ContextKey(packageRef, member));
        if (exact != null) {
            return exact;
        }
        Set<String> candidates = refsByPackage.get(packageRef);
        return candidates == null || candidates.isEmpty()
                ? null
                : candidates.iterator().next();
    }

    private static String refOf(LockPackage lockPackage) {
        return LockDependencyEdge.of(lockPackage).encode();
    }

    private record ContextKey(String packageRef, String member) {
    }
}
