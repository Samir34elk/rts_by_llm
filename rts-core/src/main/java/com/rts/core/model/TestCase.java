package com.rts.core.model;

import java.util.Set;

/**
 * Represents a single JUnit test method and the production code elements it covers.
 *
 * @param className       the fully-qualified name of the test class
 * @param methodName      the test method name
 * @param coveredElements fully-qualified names of production code elements covered by this test
 */
public record TestCase(
        String className,
        String methodName,
        Set<String> coveredElements
) {
    /**
     * Returns a unique identifier combining class and method name.
     */
    public String id() {
        return className + "#" + methodName;
    }
}
