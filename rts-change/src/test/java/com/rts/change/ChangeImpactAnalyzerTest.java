package com.rts.change;

import com.rts.analyzer.DependencyGraphBuilder;
import com.rts.analyzer.JavaAstAnalyzer;
import com.rts.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeImpactAnalyzerTest {

    private ChangeImpactAnalyzer analyzer;
    private DependencyGraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        analyzer = new ChangeImpactAnalyzer();
        graphBuilder = new DependencyGraphBuilder(new JavaAstAnalyzer());
    }

    @Test
    void computeImpactedClasses_includesDirectlyChangedClass() {
        DependencyGraph graph = graphBuilder.buildFromElements(List.of(
                new CodeElement("com.example.Foo", ElementType.CLASS, Set.of())
        ));

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("Foo.java", "com.example.Foo", Set.of(), ChangeType.MODIFIED)
        );

        Set<String> impacted = analyzer.computeImpactedClasses(changes, graph);

        assertThat(impacted).contains("com.example.Foo");
    }

    @Test
    void computeImpactedClasses_includesTransitiveDependents() {
        // C depends on B, B depends on A
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.B", ElementType.CLASS, Set.of("com.example.A")),
                new CodeElement("com.example.C", ElementType.CLASS, Set.of("com.example.B"))
        );
        DependencyGraph graph = graphBuilder.buildFromElements(elements);

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("A.java", "com.example.A", Set.of("method"), ChangeType.MODIFIED)
        );

        Set<String> impacted = analyzer.computeImpactedClasses(changes, graph);

        assertThat(impacted).containsExactlyInAnyOrder(
                "com.example.A", "com.example.B", "com.example.C");
    }

    @Test
    void computeImpactedClasses_returnsEmptyForNoChanges() {
        DependencyGraph graph = graphBuilder.buildFromElements(List.of());

        Set<String> impacted = analyzer.computeImpactedClasses(List.of(), graph);

        assertThat(impacted).isEmpty();
    }

    @Test
    void computeImpactedClasses_skipsChangesWithEmptyFqn() {
        DependencyGraph graph = graphBuilder.buildFromElements(List.of());
        List<ChangeInfo> changes = List.of(
                new ChangeInfo("file.java", "", Set.of(), ChangeType.MODIFIED)
        );

        Set<String> impacted = analyzer.computeImpactedClasses(changes, graph);

        assertThat(impacted).isEmpty();
    }

    @Test
    void directlyChangedClasses_returnsOnlyChangedFqns() {
        List<ChangeInfo> changes = List.of(
                new ChangeInfo("Foo.java", "com.example.Foo", Set.of(), ChangeType.MODIFIED),
                new ChangeInfo("Bar.java", "com.example.Bar", Set.of(), ChangeType.ADDED)
        );

        Set<String> direct = analyzer.directlyChangedClasses(changes);

        assertThat(direct).containsExactlyInAnyOrder("com.example.Foo", "com.example.Bar");
    }

    @Test
    void computeImpactedClasses_handlesMultipleChanges() {
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.B", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.C", ElementType.CLASS, Set.of("com.example.A")),
                new CodeElement("com.example.D", ElementType.CLASS, Set.of("com.example.B"))
        );
        DependencyGraph graph = graphBuilder.buildFromElements(elements);

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("A.java", "com.example.A", Set.of(), ChangeType.MODIFIED),
                new ChangeInfo("B.java", "com.example.B", Set.of(), ChangeType.MODIFIED)
        );

        Set<String> impacted = analyzer.computeImpactedClasses(changes, graph);

        assertThat(impacted).containsExactlyInAnyOrder(
                "com.example.A", "com.example.B", "com.example.C", "com.example.D");
    }
}
