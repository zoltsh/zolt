package sh.zolt.toml.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Closed member and presence metadata for one manifest inline object. */
public record ManifestObjectShape(
        List<ManifestObjectMember> members,
        List<PresenceGroup> presenceGroups) {
    private static final Comparator<ManifestObjectMember> MEMBER_ORDER = Comparator
            .comparingInt(ManifestObjectMember::canonicalOrder)
            .thenComparing(ManifestObjectMember::name);

    public ManifestObjectShape {
        Objects.requireNonNull(members, "Manifest object members are required.");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("Manifest object shapes must declare at least one member.");
        }
        ArrayList<ManifestObjectMember> orderedMembers = new ArrayList<>(members.size());
        LinkedHashMap<String, ManifestObjectMember> membersByName = new LinkedHashMap<>();
        Set<Integer> orders = new HashSet<>();
        for (ManifestObjectMember member : members) {
            ManifestObjectMember value = Objects.requireNonNull(
                    member, "Manifest object members must not contain null.");
            if (membersByName.putIfAbsent(value.name(), value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate manifest object member name `" + value.name() + "`.");
            }
            if (!orders.add(value.canonicalOrder())) {
                throw new IllegalArgumentException(
                        "Duplicate manifest object member canonical order `"
                                + value.canonicalOrder() + "`.");
            }
            orderedMembers.add(value);
        }
        orderedMembers.sort(MEMBER_ORDER);
        members = List.copyOf(orderedMembers);

        Objects.requireNonNull(presenceGroups, "Manifest object presence groups are required.");
        ArrayList<PresenceGroup> groups = new ArrayList<>(presenceGroups.size());
        Set<PresenceGroup> uniqueGroups = new HashSet<>();
        for (PresenceGroup group : presenceGroups) {
            PresenceGroup value = Objects.requireNonNull(
                    group, "Manifest object presence groups must not contain null.");
            for (ManifestObjectMember member : value.members()) {
                if (!member.equals(membersByName.get(member.name()))) {
                    throw new IllegalArgumentException(
                            "Manifest object presence group references unknown member `"
                                    + member.name() + "`.");
                }
            }
            if (!uniqueGroups.add(value)) {
                throw new IllegalArgumentException("Duplicate manifest object presence group.");
            }
            groups.add(value);
        }
        presenceGroups = List.copyOf(groups);
    }

    public Optional<ManifestObjectMember> member(String name) {
        Objects.requireNonNull(name, "Manifest object member lookup name is required.");
        return members.stream().filter(member -> member.name().equals(name)).findFirst();
    }

    /** The two structural presence rules required by the initial closed object catalog. */
    public enum PresenceRule {
        AT_LEAST_ONE,
        EXACTLY_ONE
    }

    /** One presence rule over two or more members of the enclosing shape. */
    public record PresenceGroup(
            PresenceRule rule,
            List<ManifestObjectMember> members) {
        public PresenceGroup {
            Objects.requireNonNull(rule, "Manifest object presence rule is required.");
            Objects.requireNonNull(members, "Manifest object presence members are required.");
            if (members.size() < 2) {
                throw new IllegalArgumentException(
                        "Manifest object presence groups must contain at least two members.");
            }
            ArrayList<ManifestObjectMember> ordered = new ArrayList<>(members.size());
            Set<String> names = new HashSet<>();
            for (ManifestObjectMember member : members) {
                ManifestObjectMember value = Objects.requireNonNull(
                        member, "Manifest object presence members must not contain null.");
                if (!names.add(value.name())) {
                    throw new IllegalArgumentException(
                            "Duplicate manifest object presence member `" + value.name() + "`.");
                }
                ordered.add(value);
            }
            ordered.sort(MEMBER_ORDER);
            members = List.copyOf(ordered);
        }
    }
}
