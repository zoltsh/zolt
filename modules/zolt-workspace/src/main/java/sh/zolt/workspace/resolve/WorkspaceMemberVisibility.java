package sh.zolt.workspace.resolve;

import java.util.Set;

/**
 * Which workspace members one member's lanes can see.
 *
 * <p>The workspace project graph decides this — scope by scope, and without flattening API export
 * boundaries — and {@link WorkspaceMemberLaneClosure} turns it into lock-package membership. Naming
 * it as an interface keeps the graph itself where it belongs while letting the lane rules live beside
 * the lock they read: the closure asks for a lane's visible members rather than being handed a set
 * per call, so two callers cannot ask about "the same lane" with different closures.
 */
public interface WorkspaceMemberVisibility {
    /** The members whose output and exported API the member compiles its main sources against. */
    Set<String> mainCompile(String memberPath);

    /** The members the main runtime and package closures reach. */
    Set<String> mainRuntime(String memberPath);

    /** The main runtime closure plus each direct test dependency and its own runtime closure. */
    Set<String> test(String memberPath);
}
