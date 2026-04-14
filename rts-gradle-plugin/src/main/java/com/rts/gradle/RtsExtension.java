package com.rts.gradle;

import org.gradle.api.provider.Property;

/**
 * DSL extension exposed by {@link RtsPlugin} under the {@code rts { }} block.
 *
 * <p>All properties can be overridden at the command line via project properties:
 * <ul>
 *   <li>{@code -PrtsDiff=HEAD~1..HEAD}</li>
 *   <li>{@code -PrtsChangedFiles=src/main/java/Foo.java,src/main/java/Bar.java}</li>
 *   <li>{@code -PrtsMode=hybrid}</li>
 * </ul>
 *
 * <p>Example {@code build.gradle.kts} configuration:
 * <pre>{@code
 * rts {
 *     mode = "static"          // "static" (default) or "hybrid" (LLM-assisted)
 *     diff = "HEAD~1..HEAD"    // optional default diff range
 * }
 * }</pre>
 */
public abstract class RtsExtension {

    /**
     * Git diff range to analyse, e.g. {@code "HEAD~1..HEAD"} or {@code "main..feature/my-branch"}.
     * Overridden by {@code -PrtsDiff=<range>} at the command line.
     */
    public abstract Property<String> getDiff();

    /**
     * Comma-separated list of changed file paths relative to the project root.
     * Alternative to {@link #getDiff()} when no git history is available.
     * Overridden by {@code -PrtsChangedFiles=<files>} at the command line.
     */
    public abstract Property<String> getChangedFiles();

    /**
     * Selection mode: {@code "static"} (default) or {@code "hybrid"} (LLM-assisted).
     * Overridden by {@code -PrtsMode=<mode>} at the command line.
     */
    public abstract Property<String> getMode();
}
