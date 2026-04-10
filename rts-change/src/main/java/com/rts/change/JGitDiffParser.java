package com.rts.change;

import com.rts.analyzer.JavaAstAnalyzer;
import com.rts.core.model.ChangeInfo;
import com.rts.core.model.ChangeType;
import com.rts.core.model.CodeElement;
import com.rts.core.spi.DiffParser;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * {@link DiffParser} implementation backed by JGit.
 *
 * <p>Uses the JGit API to compare two tree iterators and extract structured
 * change information without invoking external git processes.
 */
public class JGitDiffParser implements DiffParser {

    private static final Logger log = LoggerFactory.getLogger(JGitDiffParser.class);

    private final JavaAstAnalyzer astAnalyzer;

    /**
     * Creates a parser backed by the given AST analyzer (used to resolve class names
     * from file content).
     *
     * @param astAnalyzer analyzer used to resolve class FQNs from Java source
     */
    public JGitDiffParser(JavaAstAnalyzer astAnalyzer) {
        this.astAnalyzer = astAnalyzer;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code fromRef} and {@code toRef} parameters accept any ref that JGit can resolve
     * (commit hashes, branch names, {@code HEAD~1}, etc.).
     */
    @Override
    public List<ChangeInfo> parseChanges(Path repoRoot, String fromRef, String toRef) {
        log.info("Parsing diff {}..{} in {}", fromRef, toRef, repoRoot);
        try (Repository repo = openRepository(repoRoot);
             Git git = new Git(repo)) {

            AbstractTreeIterator fromTree = prepareTreeParser(repo, fromRef);
            AbstractTreeIterator toTree = prepareTreeParser(repo, toRef);

            List<DiffEntry> diffs = git.diff()
                    .setOldTree(fromTree)
                    .setNewTree(toTree)
                    .call();

            return convertEntries(diffs, repoRoot, repo);

        } catch (Exception e) {
            log.error("Failed to parse git diff {}..{}: {}", fromRef, toRef, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads each file's content directly from disk; no git history is required.
     */
    @Override
    public List<ChangeInfo> parseChangedFiles(Path repoRoot, List<String> changedFiles) {
        List<ChangeInfo> changes = new ArrayList<>();
        for (String relativePath : changedFiles) {
            Path filePath = repoRoot.resolve(relativePath);
            if (!filePath.toString().endsWith(".java")) {
                continue;
            }
            ChangeType type = Files.exists(filePath) ? ChangeType.MODIFIED : ChangeType.DELETED;
            String fqn = resolveFqnFromFile(filePath, repoRoot);
            Set<String> methods = resolveChangedMethods(filePath);
            changes.add(new ChangeInfo(relativePath, fqn, methods, type));
        }
        return changes;
    }

    // -------------------------------------------------------------------------

    private List<ChangeInfo> convertEntries(List<DiffEntry> entries, Path repoRoot, Repository repo) {
        List<ChangeInfo> result = new ArrayList<>();
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            for (DiffEntry entry : entries) {
                String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath()
                        : entry.getNewPath();

                if (!path.endsWith(".java")) {
                    continue;
                }

                ChangeType changeType = mapChangeType(entry.getChangeType());
                Path absolutePath = repoRoot.resolve(path);
                String fqn = resolveFqnFromFile(absolutePath, repoRoot);
                Set<String> methods = resolveChangedMethods(absolutePath);

                result.add(new ChangeInfo(path, fqn, methods, changeType));
            }
        }
        return result;
    }

    private ChangeType mapChangeType(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> ChangeType.ADDED;
            case DELETE -> ChangeType.DELETED;
            default -> ChangeType.MODIFIED;
        };
    }

    private String resolveFqnFromFile(Path file, Path repoRoot) {
        if (!Files.exists(file)) {
            // Derive from path when file is deleted
            return pathToFqn(file, repoRoot);
        }
        List<CodeElement> elements = astAnalyzer.analyzeFile(file);
        return elements.stream()
                .filter(CodeElement::isClassLike)
                .map(CodeElement::fullyQualifiedName)
                .findFirst()
                .orElse(pathToFqn(file, repoRoot));
    }

    private Set<String> resolveChangedMethods(Path file) {
        if (!Files.exists(file)) {
            return Set.of();
        }
        List<CodeElement> elements = astAnalyzer.analyzeFile(file);
        Set<String> methods = new HashSet<>();
        for (CodeElement e : elements) {
            if (e.type() == com.rts.core.model.ElementType.METHOD) {
                String methodName = e.fullyQualifiedName();
                if (methodName.contains("#")) {
                    methods.add(methodName.substring(methodName.indexOf('#') + 1));
                }
            }
        }
        return methods;
    }

    /**
     * Converts a file path to a best-effort FQN by stripping the src prefix and
     * replacing path separators with dots.
     */
    private String pathToFqn(Path file, Path repoRoot) {
        String relative = repoRoot.relativize(file).toString().replace('\\', '/');
        // Strip common source prefixes
        for (String prefix : List.of("src/main/java/", "src/test/java/", "src/")) {
            if (relative.startsWith(prefix)) {
                relative = relative.substring(prefix.length());
                break;
            }
        }
        return relative.replace('/', '.').replaceAll("\\.java$", "");
    }

    private Repository openRepository(Path repoRoot) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(repoRoot.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .setMustExist(true)
                .build();
    }

    private AbstractTreeIterator prepareTreeParser(Repository repo, String ref) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(repo.resolve(ref));
            var treeId = commit.getTree().getId();
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser treeParser = new CanonicalTreeParser();
                treeParser.reset(reader, treeId);
                return treeParser;
            }
        }
    }
}
