package com.rts.selector;

import com.rts.analyzer.DependencyGraphBuilder;
import com.rts.analyzer.JavaAstAnalyzer;
import com.rts.change.ChangeImpactAnalyzer;
import com.rts.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StaticSelectorTest {

    private StaticSelector selector;
    private DependencyGraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        selector = new StaticSelector(new ChangeImpactAnalyzer());
        graphBuilder = new DependencyGraphBuilder(new JavaAstAnalyzer());
    }

    @Test
    void select_returnsEmptyWhenNoChanges() {
        DependencyGraph graph = graphBuilder.buildFromElements(List.of());
        List<TestCase> tests = List.of(
                new TestCase("com.example.FooTest", "testSomething", Set.of("com.example.Foo"))
        );

        SelectionResult result = selector.select(graph, List.of(), tests);

        assertThat(result.selectedTests()).isEmpty();
        assertThat(result.source()).isEqualTo(SelectionSource.STATIC);
    }

    @Test
    void select_picksTestCoveringChangedClass() {
        // Setup: Foo depends on nothing, FooTest covers Foo
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.Foo", ElementType.CLASS, Set.of())
        );
        DependencyGraph graph = graphBuilder.buildFromElements(elements);

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("src/main/java/com/example/Foo.java", "com.example.Foo",
                        Set.of("doSomething"), ChangeType.MODIFIED)
        );
        List<TestCase> tests = List.of(
                new TestCase("com.example.FooTest", "testDoSomething", Set.of("com.example.Foo")),
                new TestCase("com.example.BarTest", "testBar", Set.of("com.example.Bar"))
        );

        SelectionResult result = selector.select(graph, changes, tests);

        assertThat(result.selectedTests()).hasSize(1);
        assertThat(result.selectedTests().get(0).className()).isEqualTo("com.example.FooTest");
    }

    @Test
    void select_includesTransitiveDependentTests() {
        // Service depends on Repository; ServiceTest covers Service → must run when Repository changes
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.Repository", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.Service", ElementType.CLASS, Set.of("com.example.Repository"))
        );
        DependencyGraph graph = graphBuilder.buildFromElements(elements);

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("src/.../Repository.java", "com.example.Repository",
                        Set.of("findAll"), ChangeType.MODIFIED)
        );
        List<TestCase> tests = List.of(
                new TestCase("com.example.ServiceTest", "testService", Set.of("com.example.Service")),
                new TestCase("com.example.RepositoryTest", "testRepo", Set.of("com.example.Repository"))
        );

        SelectionResult result = selector.select(graph, changes, tests);

        assertThat(result.selectedTests())
                .extracting(TestCase::className)
                .containsExactlyInAnyOrder("com.example.ServiceTest", "com.example.RepositoryTest");
    }

    @Test
    void select_doesNotIncludeUnrelatedTests() {
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.Foo", ElementType.CLASS, Set.of()),
                new CodeElement("com.example.Bar", ElementType.CLASS, Set.of())
        );
        DependencyGraph graph = graphBuilder.buildFromElements(elements);

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("Foo.java", "com.example.Foo", Set.of(), ChangeType.MODIFIED)
        );
        List<TestCase> tests = List.of(
                new TestCase("com.example.FooTest", "testFoo", Set.of("com.example.Foo")),
                new TestCase("com.example.BarTest", "testBar", Set.of("com.example.Bar"))
        );

        SelectionResult result = selector.select(graph, changes, tests);

        assertThat(result.selectedTests()).hasSize(1);
        assertThat(result.selectedTests().get(0).className()).isEqualTo("com.example.FooTest");
    }

    @Test
    void select_includesTestClassWhenItItselfChanged() {
        DependencyGraph graph = graphBuilder.buildFromElements(List.of(
                new CodeElement("com.example.FooTest", ElementType.CLASS, Set.of())
        ));

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("FooTest.java", "com.example.FooTest", Set.of(), ChangeType.MODIFIED)
        );
        List<TestCase> tests = List.of(
                new TestCase("com.example.FooTest", "testFoo", Set.of())
        );

        SelectionResult result = selector.select(graph, changes, tests);

        assertThat(result.selectedTests()).hasSize(1);
    }

    @Test
    void select_producesReasoning() {
        List<CodeElement> elements = List.of(
                new CodeElement("com.example.A", ElementType.CLASS, Set.of())
        );
        DependencyGraph graph = graphBuilder.buildFromElements(elements);

        List<ChangeInfo> changes = List.of(
                new ChangeInfo("A.java", "com.example.A", Set.of(), ChangeType.MODIFIED)
        );
        List<TestCase> tests = List.of(
                new TestCase("com.example.ATest", "testA", Set.of("com.example.A"))
        );

        SelectionResult result = selector.select(graph, changes, tests);

        assertThat(result.reasoning()).containsKey("com.example.ATest#testA");
    }
}
