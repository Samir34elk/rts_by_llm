package com.rts.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rts.core.model.CodeElement;
import com.rts.core.model.DependencyGraph;
import com.rts.core.model.ElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/**
 * Persists and restores a {@link DependencyGraph} to/from a JSON cache file.
 *
 * <p>The cache file is stored at {@code <projectRoot>/.rts-cache/graph.json}.
 * A cache hit requires that the cache file is newer than every {@code .java}
 * source file in the project — any source change invalidates the cache.
 *
 * <p>Usage:
 * <pre>{@code
 * GraphCache cache = new GraphCache(projectRoot);
 * Optional<DependencyGraph> hit = cache.load();
 * if (hit.isEmpty()) {
 *     DependencyGraph graph = builder.buildFromProject(projectRoot);
 *     cache.save(graph);
 * }
 * }</pre>
 */
public class GraphCache {

    private static final Logger log = LoggerFactory.getLogger(GraphCache.class);
    private static final String CACHE_DIR  = ".rts-cache";
    private static final String CACHE_FILE = "graph.json";

    private final Path cacheFile;
    private final Path projectRoot;
    private final ObjectMapper mapper;

    /**
     * Creates a cache manager for the given project root.
     *
     * @param projectRoot root directory of the project
     */
    public GraphCache(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.cacheFile   = projectRoot.resolve(CACHE_DIR).resolve(CACHE_FILE);
        this.mapper      = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Returns the path of the cache file (whether or not it exists yet).
     *
     * @return path to the cache file
     */
    public Path getCacheFile() {
        return cacheFile;
    }

    /**
     * Attempts to load a valid cached graph.
     *
     * <p>Returns empty if:
     * <ul>
     *   <li>The cache file does not exist</li>
     *   <li>Any {@code .java} source file is newer than the cache</li>
     *   <li>The cache file is corrupted or unreadable</li>
     * </ul>
     *
     * @return the cached graph, or empty on miss
     */
    public Optional<DependencyGraph> load() {
        if (!Files.exists(cacheFile)) {
            log.debug("Cache miss: file not found ({})", cacheFile);
            return Optional.empty();
        }

        try {
            Instant cacheTime = Files.getLastModifiedTime(cacheFile).toInstant();
            if (isAnySrcNewerThan(cacheTime)) {
                log.info("Cache invalidated: source files changed since {}", cacheTime);
                return Optional.empty();
            }

            CachePayload payload = mapper.readValue(cacheFile.toFile(), CachePayload.class);
            DependencyGraph graph = deserialize(payload);
            log.info("Cache hit: loaded graph ({} elements) from {}", graph.size(), cacheFile);
            return Optional.of(graph);

        } catch (IOException e) {
            log.warn("Cache unreadable ({}): {}", cacheFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persists the given graph to the cache file, creating the cache directory
     * if necessary.
     *
     * @param graph graph to persist
     */
    public void save(DependencyGraph graph) {
        try {
            Files.createDirectories(cacheFile.getParent());
            CachePayload payload = serialize(graph);
            mapper.writeValue(cacheFile.toFile(), payload);
            log.info("Cache saved: {} elements → {}", graph.size(), cacheFile);
        } catch (IOException e) {
            log.warn("Could not write cache ({}): {}", cacheFile, e.getMessage());
        }
    }

    /**
     * Deletes the cache file if it exists.
     */
    public void invalidate() {
        try {
            Files.deleteIfExists(cacheFile);
            log.info("Cache invalidated: deleted {}", cacheFile);
        } catch (IOException e) {
            log.warn("Could not delete cache file: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private boolean isAnySrcNewerThan(Instant threshold) throws IOException {
        try (var stream = Files.walk(projectRoot)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(cacheFile.getParent()))
                    .anyMatch(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isAfter(threshold);
                        } catch (IOException e) {
                            return true; // conservative: treat unreadable as newer
                        }
                    });
        }
    }

    private CachePayload serialize(DependencyGraph graph) {
        List<ElementPayload> elements = new ArrayList<>();
        for (var element : graph.getAllElements()) {
            elements.add(new ElementPayload(
                    element.fullyQualifiedName(),
                    element.type().name(),
                    new ArrayList<>(element.dependencies())
            ));
        }
        return new CachePayload(Instant.now().toString(), elements);
    }

    private DependencyGraph deserialize(CachePayload payload) {
        DependencyGraph graph = new DependencyGraph();
        for (ElementPayload ep : payload.elements()) {
            ElementType type = ElementType.valueOf(ep.type());
            CodeElement element = new CodeElement(ep.fqn(), type, new HashSet<>(ep.dependencies()));
            graph.addElement(element);
        }
        // Wire edges
        for (ElementPayload ep : payload.elements()) {
            if (ElementType.valueOf(ep.type()) != ElementType.METHOD) {
                for (String dep : ep.dependencies()) {
                    if (graph.contains(dep)) {
                        graph.addDependency(ep.fqn(), dep);
                    }
                }
            } else {
                String owningClass = ep.fqn().contains("#")
                        ? ep.fqn().substring(0, ep.fqn().indexOf('#'))
                        : ep.fqn();
                for (String dep : ep.dependencies()) {
                    if (graph.contains(dep) && !dep.equals(owningClass)) {
                        graph.addDependency(owningClass, dep);
                    }
                }
            }
        }
        return graph;
    }

    // ── JSON payload records ──────────────────────────────────────────────────

    record CachePayload(String generatedAt, List<ElementPayload> elements) {
        // Jackson needs a no-arg constructor for deserialization
        @SuppressWarnings("unused")
        public CachePayload() { this(null, null); }
    }

    record ElementPayload(String fqn, String type, List<String> dependencies) {
        @SuppressWarnings("unused")
        public ElementPayload() { this(null, null, null); }
    }
}
