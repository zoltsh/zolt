package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AuthoredTestRuntimeTest {
    @Test
    void copiesLiteralValuesAndUsesFixedEventAndCodePointOrder() {
        ArrayList<String> arguments = new ArrayList<>(List.of("-Xmx2g"));
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        properties.put("\ud800\udc00", "supplementary");
        properties.put("\ue000", "bmp");
        AuthoredTestRuntime runtime = new AuthoredTestRuntime(
                arguments,
                properties,
                Map.of(new EnvironmentVariableName("APP_ENV"), "test"),
                List.of(AuthoredTestRuntime.Event.FAILED, AuthoredTestRuntime.Event.PASSED));
        arguments.clear();
        properties.clear();

        assertEquals(List.of("-Xmx2g"), runtime.jvmArgs());
        assertEquals(List.of("\ue000", "\ud800\udc00"), List.copyOf(runtime.properties().keySet()));
        assertEquals(
                List.of(AuthoredTestRuntime.Event.PASSED, AuthoredTestRuntime.Event.FAILED),
                runtime.events());
        assertThrows(UnsupportedOperationException.class, () -> runtime.properties().clear());
    }

    @Test
    void rejectsDuplicateEventsAndEnvironmentCaseCollisions() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestRuntime(
                List.of(),
                Map.of(),
                Map.of(),
                List.of(AuthoredTestRuntime.Event.FAILED, AuthoredTestRuntime.Event.FAILED)));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestRuntime(
                List.of(),
                Map.of(),
                Map.of(
                        new EnvironmentVariableName("APP_ENV"), "one",
                        new EnvironmentVariableName("app_env"), "two"),
                List.of()));
    }

    @Test
    void rejectsTheRemovedProjectRootPlaceholderAcrossLiteralRuntimeValues() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestRuntime(
                List.of("-Droot=${project.root}"), Map.of(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestRuntime(
                List.of(), Map.of("root", "${project.root}/build"), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestRuntime(
                List.of(),
                Map.of(),
                Map.of(new EnvironmentVariableName("ROOT"), "${project.root}"),
                List.of()));

        assertEquals(
                List.of("${literal}"),
                new AuthoredTestRuntime(List.of("${literal}"), Map.of(), Map.of(), List.of()).jvmArgs());
    }

    @Test
    void rejectsAnEmptyRuntimeSingleton() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestRuntime(
                List.of(), Map.of(), Map.of(), List.of()));
    }
}
