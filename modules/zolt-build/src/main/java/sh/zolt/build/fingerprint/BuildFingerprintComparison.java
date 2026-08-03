package sh.zolt.build.fingerprint;

import java.util.LinkedHashMap;
import java.util.Map;

final class BuildFingerprintComparison {
    BuildFingerprintCheck compare(String existing, String current) {
        if (existing.equals(current)) {
            return BuildFingerprintCheck.hit();
        }
        return BuildFingerprintCheck.miss("fingerprint-mismatch:" + firstChangedComponent(existing, current));
    }

    BuildFingerprintCheck compareForCompilation(String existing, String current) {
        Map<String, String> existingComponents = components(existing);
        Map<String, String> currentComponents = components(current);
        existingComponents.remove("resources");
        currentComponents.remove("resources");
        String existingCompile = joined(existingComponents);
        String currentCompile = joined(currentComponents);
        if (existingCompile.equals(currentCompile)) {
            return existing.equals(current)
                    ? BuildFingerprintCheck.hit()
                    : BuildFingerprintCheck.hit("fingerprint-mismatch:resources");
        }
        return BuildFingerprintCheck.miss(
                "fingerprint-mismatch:" + firstChangedComponent(existingCompile, currentCompile));
    }

    private static String firstChangedComponent(String existing, String current) {
        Map<String, String> existingComponents = components(existing);
        Map<String, String> currentComponents = components(current);
        for (String name : existingComponents.keySet()) {
            if (!existingComponents.get(name).equals(currentComponents.get(name))) {
                return name;
            }
        }
        for (String name : currentComponents.keySet()) {
            if (!existingComponents.containsKey(name)) {
                return name;
            }
        }
        return "unknown";
    }

    private static Map<String, String> components(String fingerprint) {
        Map<String, StringBuilder> values = new LinkedHashMap<>();
        String section = null;
        for (String line : fingerprint.lines().toList()) {
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                values.putIfAbsent(section, new StringBuilder());
                values.get(section).append(line).append('\n');
                continue;
            }
            String component = section == null && line.contains("=")
                    ? line.substring(0, line.indexOf('='))
                    : section == null ? "header" : section;
            values.putIfAbsent(component, new StringBuilder());
            values.get(component).append(line).append('\n');
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((name, value) -> result.put(name, value.toString()));
        return result;
    }

    private static String joined(Map<String, String> components) {
        StringBuilder content = new StringBuilder();
        components.values().forEach(content::append);
        return content.toString();
    }
}
