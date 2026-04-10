package com.rts.core.spi;

import com.rts.core.model.TestCase;

import java.nio.file.Path;
import java.util.List;

/**
 * Discovers test cases within a Java project source tree.
 */
public interface TestDiscovery {

    /**
     * Scans the given project root for JUnit test classes and methods.
     *
     * @param projectRoot root directory of the project (must contain {@code src/test/})
     * @return list of discovered test cases; never {@code null}, may be empty
     */
    List<TestCase> discoverTests(Path projectRoot);
}
