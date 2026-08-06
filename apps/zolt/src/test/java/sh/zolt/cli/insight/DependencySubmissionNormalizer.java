package sh.zolt.cli.insight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The normalization the GitHub dependency-submission action applies before it cross-checks
 * {@code zolt tree --workspace --format json} against {@code zolt sbom --workspace}. Both documents
 * are reduced to the same node identity — {@code groupId:artifactId:version:variantKey} — and the same
 * edge set:
 *
 * <ul>
 *   <li>first-party nodes (the workspace root and its member components) are removed, together with
 *       every edge that starts or ends at one;</li>
 *   <li>scope copies of one artifact collapse onto a single node, because a purl carries no scope;</li>
 *   <li>per-member SBOM contexts ({@code purl#zolt-context=<member>}) collapse onto their purl and
 *       their children are unioned.</li>
 * </ul>
 *
 * <p>Tree edges are parsed with the action's single strict parser: exactly
 * {@code groupId:artifactId:version:extension|classifier:scope}. A shorter historical edge is a
 * contract violation, not something to be repaired here, so it fails loudly.
 */
final class DependencySubmissionNormalizer {
    private DependencySubmissionNormalizer() {
    }

    /** The external edge set of the schema-3 tree document, as {@code source -> target} pairs. */
    static Set<String> treeEdges(String treeJson, Set<String> firstParty) {
        Set<String> edges = new TreeSet<>();
        String id = "";
        String version = "";
        String variant = "jar";
        for (String line : treeJson.lines().toList()) {
            switch (key(line)) {
                case "id" -> {
                    id = stringValue(line);
                    variant = "jar";
                }
                case "version" -> version = stringValue(line);
                case "variant" -> variant = stringValue(line);
                case "dependencies" -> {
                    String source = id + ":" + version + ":" + variant;
                    for (String edge : arrayValue(line)) {
                        addEdge(edges, firstParty, source, treeNode(edge));
                    }
                }
                default -> {
                }
            }
        }
        return edges;
    }

    /** The external edge set of the CycloneDX document, with contexts collapsed and children unioned. */
    static Set<String> sbomEdges(String sbomJson, Set<String> firstParty) {
        Set<String> edges = new TreeSet<>();
        for (var entry : sbomGraph(sbomJson).entrySet()) {
            String source = sbomNode(entry.getKey());
            for (String target : entry.getValue()) {
                addEdge(edges, firstParty, source, sbomNode(target));
            }
        }
        return edges;
    }

    /**
     * The first-party node identities: the workspace root plus every member component it contains.
     * The action reads them from the BOM rather than the workspace config, so one rule removes them
     * from both documents.
     */
    static Set<String> firstPartyNodes(String sbomJson) {
        Set<String> firstParty = new TreeSet<>();
        for (var entry : sbomGraph(sbomJson).entrySet()) {
            if (!entry.getKey().startsWith("workspace:")) {
                continue;
            }
            firstParty.add(sbomNode(entry.getKey()));
            entry.getValue().forEach(member -> firstParty.add(sbomNode(member)));
        }
        return firstParty;
    }

    private static void addEdge(Set<String> edges, Set<String> firstParty, String source, String target) {
        if (firstParty.contains(source) || firstParty.contains(target)) {
            return;
        }
        edges.add(source + " -> " + target);
    }

    /** {@code ref -> dependsOn} exactly as the BOM records it, before any collapsing. */
    private static Map<String, List<String>> sbomGraph(String sbomJson) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        String ref = "";
        for (String line : sbomJson.lines().toList()) {
            switch (key(line)) {
                case "ref" -> ref = stringValue(line);
                case "dependsOn" -> graph.put(ref, arrayValue(line));
                default -> {
                }
            }
        }
        return graph;
    }

    /** A canonical schema-3 edge string reduced to its scope-free node identity. */
    private static String treeNode(String edge) {
        String[] parts = edge.split(":", -1);
        if (parts.length != 5) {
            throw new AssertionError(
                    "Tree edge `" + edge + "` is not the canonical "
                            + "groupId:artifactId:version:variant:scope form the action parses.");
        }
        return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
    }

    /**
     * A CycloneDX {@code bom-ref} reduced to the same identity: the context fragment is dropped, and a
     * Maven purl becomes {@code group:name:version:type[|classifier]}. Non-purl refs (the workspace
     * root) are returned verbatim so they can still be recognised as first-party.
     */
    private static String sbomNode(String ref) {
        int fragment = ref.indexOf('#');
        String purl = fragment < 0 ? ref : ref.substring(0, fragment);
        if (!purl.startsWith("pkg:maven/")) {
            return purl;
        }
        String body = purl.substring("pkg:maven/".length());
        int query = body.indexOf('?');
        String path = query < 0 ? body : body.substring(0, query);
        String type = "jar";
        String classifier = "";
        if (query >= 0) {
            for (String qualifier : body.substring(query + 1).split("&")) {
                int equals = qualifier.indexOf('=');
                String name = qualifier.substring(0, equals);
                String value = qualifier.substring(equals + 1);
                if ("type".equals(name)) {
                    type = value;
                } else if ("classifier".equals(name)) {
                    classifier = value;
                }
            }
        }
        String group = path.substring(0, path.indexOf('/'));
        String rest = path.substring(path.indexOf('/') + 1);
        String name = rest.substring(0, rest.indexOf('@'));
        String version = rest.substring(rest.indexOf('@') + 1);
        String variant = classifier.isEmpty() ? type : type + "|" + classifier;
        return group + ":" + name + ":" + version + ":" + variant;
    }

    private static String key(String line) {
        String trimmed = line.trim();
        int end = trimmed.indexOf("\":");
        return trimmed.startsWith("\"") && end > 0 ? trimmed.substring(1, end) : "";
    }

    private static String stringValue(String line) {
        String trimmed = line.trim();
        return trimmed.substring(trimmed.indexOf(": \"") + 3, trimmed.lastIndexOf('"'));
    }

    private static List<String> arrayValue(String line) {
        String trimmed = line.trim();
        String body = trimmed.substring(trimmed.indexOf('[') + 1, trimmed.lastIndexOf(']'));
        return Stream.of(body.split(","))
                .map(String::trim)
                .filter(token -> token.length() > 1)
                .map(token -> token.substring(1, token.length() - 1))
                .toList();
    }
}
