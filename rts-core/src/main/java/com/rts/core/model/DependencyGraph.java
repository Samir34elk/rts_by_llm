package com.rts.core.model;

import java.util.*;

/**
 * Directed graph where nodes are fully-qualified class names and edges represent
 * "depends on" relationships (i.e. an edge A → B means A depends on B).
 *
 * <p>The graph is stored as an adjacency list (dependents map): for each class B,
 * {@code dependents.get(B)} returns all classes A that directly depend on B.
 * This reverse index makes it efficient to find everything affected by a change to B.
 */
public class DependencyGraph {

    /** Maps a class FQN to the set of classes that directly depend on it. */
    private final Map<String, Set<String>> dependents = new HashMap<>();

    /** Maps a class FQN to the set of classes it directly depends on. */
    private final Map<String, Set<String>> dependencies = new HashMap<>();

    /** All code elements indexed by their fully-qualified name. */
    private final Map<String, CodeElement> elements = new HashMap<>();

    /**
     * Registers a {@link CodeElement} in the graph without adding any edges.
     *
     * @param element the element to register
     */
    public void addElement(CodeElement element) {
        elements.put(element.fullyQualifiedName(), element);
        dependents.computeIfAbsent(element.fullyQualifiedName(), k -> new HashSet<>());
        dependencies.computeIfAbsent(element.fullyQualifiedName(), k -> new HashSet<>());
    }

    /**
     * Records that {@code dependent} directly depends on {@code dependency}.
     *
     * @param dependent  fully-qualified name of the depending class
     * @param dependency fully-qualified name of the class being depended upon
     */
    public void addDependency(String dependent, String dependency) {
        dependencies.computeIfAbsent(dependent, k -> new HashSet<>()).add(dependency);
        dependents.computeIfAbsent(dependency, k -> new HashSet<>()).add(dependent);
        // Ensure both nodes exist in the map
        dependents.computeIfAbsent(dependent, k -> new HashSet<>());
        dependencies.computeIfAbsent(dependency, k -> new HashSet<>());
    }

    /**
     * Returns the set of classes that directly depend on {@code className}.
     *
     * @param className fully-qualified class name
     * @return immutable view of direct dependents
     */
    public Set<String> getDirectDependents(String className) {
        return Collections.unmodifiableSet(dependents.getOrDefault(className, Set.of()));
    }

    /**
     * Returns all classes that transitively depend on {@code className} (BFS/DFS traversal).
     *
     * @param className fully-qualified class name of the changed class
     * @return set of all transitive dependents (excluding the input class itself)
     */
    public Set<String> getTransitiveDependents(String className) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(className);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String dep : dependents.getOrDefault(current, Set.of())) {
                if (visited.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    /**
     * Returns the {@link CodeElement} registered under {@code fqn}, if any.
     *
     * @param fqn fully-qualified name
     * @return optional element
     */
    public Optional<CodeElement> getElement(String fqn) {
        return Optional.ofNullable(elements.get(fqn));
    }

    /**
     * Returns all registered class/interface elements (not methods).
     *
     * @return unmodifiable collection of class-like elements
     */
    public Collection<CodeElement> getAllClassElements() {
        return elements.values().stream()
                .filter(CodeElement::isClassLike)
                .toList();
    }

    /**
     * Returns all registered elements.
     *
     * @return unmodifiable collection
     */
    public Collection<CodeElement> getAllElements() {
        return Collections.unmodifiableCollection(elements.values());
    }

    /**
     * Returns {@code true} if the graph contains an entry for the given FQN.
     *
     * @param fqn fully-qualified name to check
     * @return whether the node exists
     */
    public boolean contains(String fqn) {
        return elements.containsKey(fqn);
    }

    /**
     * Returns the total number of registered elements.
     *
     * @return element count
     */
    public int size() {
        return elements.size();
    }
}
