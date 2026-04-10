package com.rts.change;

import com.rts.core.model.ChangeInfo;
import com.rts.core.model.DependencyGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Maps a list of {@link ChangeInfo} objects to the set of fully-qualified class names
 * that are directly or transitively impacted by those changes.
 *
 * <p>This class bridges the change detection layer and the static selector: it uses the
 * {@link DependencyGraph} to propagate impact from changed classes to their dependents.
 */
public class ChangeImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ChangeImpactAnalyzer.class);

    /**
     * Computes the full set of class names impacted by the given changes.
     *
     * <p>A class is considered impacted if:
     * <ul>
     *   <li>It is the class that changed directly, or</li>
     *   <li>It transitively depends (directly or indirectly) on a changed class.</li>
     * </ul>
     *
     * @param changes list of detected changes
     * @param graph   dependency graph of the project
     * @return set of impacted fully-qualified class names (never {@code null})
     */
    public Set<String> computeImpactedClasses(List<ChangeInfo> changes, DependencyGraph graph) {
        Set<String> impacted = new LinkedHashSet<>();

        for (ChangeInfo change : changes) {
            String changedClass = change.fullyQualifiedClass();
            if (changedClass == null || changedClass.isBlank()) {
                log.debug("Skipping change with empty FQN in {}", change.filePath());
                continue;
            }

            // The changed class itself is impacted
            impacted.add(changedClass);

            // All transitive dependents are also impacted
            Set<String> transitive = graph.getTransitiveDependents(changedClass);
            log.debug("Class {} has {} transitive dependents", changedClass, transitive.size());
            impacted.addAll(transitive);
        }

        log.info("Change impact: {} changes → {} impacted classes", changes.size(), impacted.size());
        return Collections.unmodifiableSet(impacted);
    }

    /**
     * Returns the directly changed class names from the given change list.
     *
     * @param changes list of changes
     * @return set of directly changed FQNs
     */
    public Set<String> directlyChangedClasses(List<ChangeInfo> changes) {
        Set<String> direct = new LinkedHashSet<>();
        for (ChangeInfo change : changes) {
            if (change.fullyQualifiedClass() != null && !change.fullyQualifiedClass().isBlank()) {
                direct.add(change.fullyQualifiedClass());
            }
        }
        return Collections.unmodifiableSet(direct);
    }
}
