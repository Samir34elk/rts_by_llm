package com.rts.core.spi;

import com.rts.core.model.ChangeInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Parses a Git diff and returns structured change information.
 */
public interface DiffParser {

    /**
     * Extracts structured changes between two Git refs within the given repository.
     *
     * @param repoRoot  root directory of the Git repository
     * @param fromRef   starting Git ref (e.g. {@code HEAD~1})
     * @param toRef     ending Git ref (e.g. {@code HEAD})
     * @return list of detected changes; never {@code null}, may be empty
     */
    List<ChangeInfo> parseChanges(Path repoRoot, String fromRef, String toRef);

    /**
     * Extracts structured changes from a set of explicitly-listed changed file paths.
     *
     * @param repoRoot     root directory of the project
     * @param changedFiles relative paths of files that changed
     * @return list of detected changes; never {@code null}, may be empty
     */
    List<ChangeInfo> parseChangedFiles(Path repoRoot, List<String> changedFiles);
}
