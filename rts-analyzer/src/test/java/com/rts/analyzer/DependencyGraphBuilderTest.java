package com.rts.analyzer;

import com.rts.core.model.CodeElement;
import com.rts.core.model.DependencyGraph;
import com.rts.core.model.ElementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyGraphBuilderTest {

    private DependencyGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DependencyGraphBuilder(new JavaAstAnalyzer());
    }

    @Test
    void buildFromElements_registersAllElements() {
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.B", ElementType.CLASS, Set.of())
        );

        DependencyGraph graph = builder.buildFromElements(elements);

        assertThat(graph.size()).isEqualTo(2);
        assertThat(graph.contains("com.example.A")).isTrue();
        assertThat(graph.contains("com.example.B")).isTrue();
    }

    @Test
    void buildFromElements_wiresEdgesFromDependencies() {
        // B depends on A
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.B", ElementType.CLASS, Set.of("com.example.A"))
        );

        DependencyGraph graph = builder.buildFromElements(elements);

        // A is depended-on by B → A's dependents should include B
        assertThat(graph.getDirectDependents("com.example.A")).contains("com.example.B");
    }

    @Test
    void getTransitiveDependents_followsTransitiveChain() {
        // C → B → A  (C depends on B, B depends on A)
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.B", ElementType.CLASS, Set.of("com.example.A")),
                new CodeElement("com.example.C", ElementType.CLASS, Set.of("com.example.B"))
        );

        DependencyGraph graph = builder.buildFromElements(elements);

        Set<String> transitive = graph.getTransitiveDependents("com.example.A");
        assertThat(transitive).containsExactlyInAnyOrder("com.example.B", "com.example.C");
    }

    @Test
    void getTransitiveDependents_handlesUnknownClass() {
        DependencyGraph graph = builder.buildFromElements(List.of());
        assertThat(graph.getTransitiveDependents("com.example.Unknown")).isEmpty();
    }

    @Test
    void buildFromElements_ignoresExternalDependencies() {
        // java.util.List is external — should not create a node for it
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of("java.util.List"))
        );

        DependencyGraph graph = builder.buildFromElements(elements);

        assertThat(graph.contains("java.util.List")).isFalse();
        assertThat(graph.getDirectDependents("com.example.A")).isEmpty();
    }

    @Test
    void buildFromElements_handlesCircularDependency() {
        // A → B → A (cycle)
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of("com.example.B")),
                new CodeElement("com.example.B", ElementType.CLASS, Set.of("com.example.A"))
        );

        DependencyGraph graph = builder.buildFromElements(elements);

        // Transitive should terminate without infinite loop
        Set<String> transitive = graph.getTransitiveDependents("com.example.A");
        assertThat(transitive).contains("com.example.B");
    }
}
