package com.rts.core.model;

import java.util.Set;

/**
 * Represents a detected change in a single source file.
 *
 * @param filePath             relative path of the changed file within the project
 * @param fullyQualifiedClass  fully-qualified name of the primary class declared in the file
 * @param changedMethods       names of methods that were added, modified, or deleted
 * @param type                 nature of the overall file change
 */
public record ChangeInfo(
        String filePath,
        String fullyQualifiedClass,
        Set<String> changedMethods,
        ChangeType type
) {}
