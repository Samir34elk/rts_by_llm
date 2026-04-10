package com.rts.core.model;

import java.util.List;
import java.util.Map;

/**
 * The final output of a test selection run.
 *
 * @param selectedTests tests that must be (re-)executed
 * @param source        which selection strategy produced this result
 * @param reasoning     optional per-test or global reasoning strings (test id → reason)
 */
public record SelectionResult(
        List<TestCase> selectedTests,
        SelectionSource source,
        Map<String, String> reasoning
) {}
