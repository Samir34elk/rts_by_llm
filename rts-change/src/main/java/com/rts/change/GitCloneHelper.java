package com.rts.change;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

/**
 * Clones a remote Git repository to a local temporary directory and provides
 * lifecycle management (cleanup) for that directory.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (GitCloneHelper clone = GitCloneHelper.clone("https://github.com/org/repo.git")) {
 *     Path repoRoot = clone.getRepoRoot();
 *     // use repoRoot for analysis
 * }
 * // temp directory is deleted on close
 * }</pre>
 */
public class GitCloneHelper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GitCloneHelper.class);

    private final Path tempDir;
    private final Git git;

    private GitCloneHelper(Path tempDir, Git git) {
        this.tempDir = tempDir;
        this.git = git;
    }

    /**
     * Clones the repository at {@code url} into a fresh temporary directory.
     *
     * @param url remote Git URL (HTTPS or SSH)
     * @return helper instance holding the cloned repo; must be closed by the caller
     * @throws GitCloneException if the clone operation fails
     */
    public static GitCloneHelper clone(String url) {
        return clone(url, null);
    }

    /**
     * Clones a specific branch of the repository at {@code url}.
     *
     * @param url    remote Git URL (HTTPS or SSH)
     * @param branch branch to checkout, or {@code null} for the default branch
     * @return helper instance holding the cloned repo; must be closed by the caller
     * @throws GitCloneException if the clone operation fails
     */
    public static GitCloneHelper clone(String url, String branch) {
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("rts-clone-");
        } catch (IOException e) {
            throw new GitCloneException("Cannot create temp directory for clone", e);
        }

        log.info("Cloning {} into {}", url, tempDir);

        try {
            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(tempDir.toFile())
                    .setProgressMonitor(new TextProgressMonitor(new PrintWriter(System.err)));

            if (branch != null && !branch.isBlank()) {
                cmd.setBranch(branch);
            }

            Git git = cmd.call();
            log.info("Clone complete: {}", tempDir);
            return new GitCloneHelper(tempDir, git);

        } catch (GitAPIException e) {
            deleteQuietly(tempDir);
            throw new GitCloneException("Failed to clone repository: " + url, e);
        }
    }

    /**
     * Returns the root directory of the cloned repository.
     *
     * @return absolute path to the repository root
     */
    public Path getRepoRoot() {
        return tempDir;
    }

    /**
     * Returns the latest commit hash (HEAD) of the cloned repository.
     *
     * @return full SHA-1 of HEAD, or {@code "UNKNOWN"} if it cannot be resolved
     */
    public String getHeadCommit() {
        try {
            return git.getRepository().resolve("HEAD").getName();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Returns the remote URL that was cloned.
     *
     * @return remote origin URL
     */
    public String getRemoteUrl() {
        try {
            return git.getRepository()
                    .getConfig()
                    .getString("remote", "origin", "url");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Closes the underlying JGit repository and deletes the temporary directory.
     */
    @Override
    public void close() {
        git.close();
        deleteQuietly(tempDir);
        log.debug("Deleted temp clone directory: {}", tempDir);
    }

    // -------------------------------------------------------------------------

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not fully delete temp directory {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Thrown when cloning a repository fails.
     */
    public static class GitCloneException extends RuntimeException {
        public GitCloneException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
