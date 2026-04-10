package com.rts.analyzer;

import com.rts.core.model.TestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestMappingResolverTest {

    private TestMappingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TestMappingResolver();
    }

    @Test
    void extractTestCases_detectsJUnit5TestMethods() {
        String source = """
                package com.example;
                import org.junit.jupiter.api.Test;
                class MyServiceTest {
                    @Test
                    void doSomething_works() {}
                    @Test
                    void doSomething_fails() {}
                }
                """;

        List<TestCase> tests = resolver.extractTestCases(source);

        assertThat(tests).hasSize(2);
        assertThat(tests).extracting(TestCase::methodName)
                .containsExactlyInAnyOrder("doSomething_works", "doSomething_fails");
    }

    @Test
    void extractTestCases_setsCorrectClassName() {
        String source = """
                package com.example.service;
                import org.junit.jupiter.api.Test;
                class OrderServiceTest {
                    @Test
                    void createOrder() {}
                }
                """;

        List<TestCase> tests = resolver.extractTestCases(source);

        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).className()).isEqualTo("com.example.service.OrderServiceTest");
    }

    @Test
    void extractTestCases_infersCoverageFromImports() {
        String source = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import com.example.service.PaymentService;
                class PaymentServiceTest {
                    @Test
                    void processPayment() {}
                }
                """;

        List<TestCase> tests = resolver.extractTestCases(source);

        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).coveredElements()).contains("com.example.service.PaymentService");
    }

    @Test
    void extractTestCases_returnsEmptyForNonTestClass() {
        String source = """
                package com.example;
                public class MyService {
                    public void doStuff() {}
                }
                """;

        List<TestCase> tests = resolver.extractTestCases(source);

        assertThat(tests).isEmpty();
    }

    @Test
    void extractTestCases_handlesClassWithNoAnnotatedMethods() {
        String source = """
                package com.example;
                class SomeTestHelper {
                    void helper() {}
                }
                """;

        List<TestCase> tests = resolver.extractTestCases(source);

        assertThat(tests).isEmpty();
    }

    @Test
    void testCase_idCombinesClassAndMethod() {
        String source = """
                package com.example;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test
                    void bar() {}
                }
                """;

        List<TestCase> tests = resolver.extractTestCases(source);

        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).id()).isEqualTo("com.example.FooTest#bar");
    }
}
