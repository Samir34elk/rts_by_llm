package com.rts.core.model;

import java.util.Set;

/**
 * Represents an identifiable element in a Java codebase (class, method, or interface).
 *
 * @param fullyQualifiedName the fully-qualified name (e.g. {@code com.example.Foo} or
 *                           {@code com.example.Foo#doSomething})
 * @param type               the kind of element
 * @param dependencies       set of fully-qualified names this element directly depends on
 */
public record CodeElement(
        String fullyQualifiedName,
        ElementType type,
        Set<String> dependencies
) {
    /**
     * Returns {@code true} if this element is a class or interface (not a method).
     */
    public boolean isClassLike() {
        return type == ElementType.CLASS || type == ElementType.INTERFACE;
    }

    /**
     * Returns the owning class name for a method element, or the element itself for class-like elements.
     */
    public String owningClass() {
        if (type == ElementType.METHOD && fullyQualifiedName.contains("#")) {
            return fullyQualifiedName.substring(0, fullyQualifiedName.indexOf('#'));
        }
        return fullyQualifiedName;
    }
}
