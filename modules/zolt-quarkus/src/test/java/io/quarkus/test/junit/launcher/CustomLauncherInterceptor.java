package io.quarkus.test.junit.launcher;

import org.junit.platform.launcher.LauncherDiscoveryListener;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;

/**
 * Hermetic stand-in for Quarkus JUnit's launcher interceptor, shadowing the real class for this
 * module's own test JVM only.
 *
 * <p>{@code io.quarkus:quarkus-junit} is a {@code provided} dependency: Zolt compiles against it but
 * never resolves its runtime closure, because design §9.2 does not propagate the provided lane. The
 * same §9.2 rule puts provided on the test lanes, so the jar's
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener} registration is
 * discovered whenever a test opens a JUnit launcher session. The real interceptor's static
 * initializer needs {@code io.quarkus.test.config.QuarkusClassOrderer}, which ships in a different
 * Quarkus artifact that this module does not depend on, so initializing it raises
 * {@code ServiceConfigurationError} and fails every session before any test runs.
 *
 * <p>{@code target/test-classes} precedes resolved jars on the test runtime classpath (see
 * {@code TestRuntimeClasspathOrder}), so this class wins the name and keeps the session hermetic.
 * {@code facadeLoader} stays null on purpose: {@code QuarkusAnnotationProgrammaticRunner} reads it
 * reflectively and skips its Quarkus classloader handoff when it is not a {@link ClassLoader}, which
 * is the same benign path a non-Quarkus project takes in production. Real Quarkus runs happen in the
 * application's own JVM, where the genuine interceptor and its runtime peers are both present.
 *
 * <p>The jar registers this one class under three launcher service interfaces, so the stand-in
 * implements all three; ServiceLoader rejects a provider that is "not a subtype" of any interface it
 * is registered for.
 */
public final class CustomLauncherInterceptor
        implements LauncherSessionListener, LauncherDiscoveryListener, TestExecutionListener {
    @SuppressWarnings("unused")
    private static final Object facadeLoader = null;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
    }
}
