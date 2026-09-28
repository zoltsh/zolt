package sh.zolt.lockfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LockfileFreshnessSummary {
    private LockfileFreshnessSummary() {
    }

    public static String changedInputs(ZoltLockfile existing, ZoltLockfile candidate) {
        Map<String, String> existingInputs = inputFingerprints(existing.projectResolutionInputFingerprints());
        Map<String, String> candidateInputs = inputFingerprints(candidate.projectResolutionInputFingerprints());
        if (existingInputs.isEmpty() || candidateInputs.isEmpty()) {
            return "";
        }
        List<String> changed = new ArrayList<>(candidateInputs.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(existingInputs.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .toList());
        existingInputs.keySet().stream()
                .filter(input -> !candidateInputs.containsKey(input))
                .forEach(changed::add);
        if (changed.isEmpty()) {
            return "";
        }
        return " Changed inputs: " + String.join(", ", changed) + ".";
    }

    private static Map<String, String> inputFingerprints(List<String> values) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String value : values) {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                continue;
            }
            inputs.put(value.substring(0, separator), value.substring(separator + 1));
        }
        return inputs;
    }
}
