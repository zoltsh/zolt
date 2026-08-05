package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import java.util.List;

/**
 * Stage 1's on-demand classpath source: a member's lanes are projected out of the root lock the
 * first time something actually asks for them, never ahead of the decision to use them.
 */
interface WorkspaceMemberClasspaths {
    ClasspathSet forMember(String memberPath);

    List<ResolvedClasspathPackage> packagesForMember(String memberPath);
}
