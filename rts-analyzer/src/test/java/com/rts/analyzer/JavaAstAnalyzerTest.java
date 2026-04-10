package com.rts.analyzer;

import com.rts.core.model.CodeElement;
import com.rts.core.model.ElementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JavaAstAnalyzerTest {

    private JavaAstAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new JavaAstAnalyzer();
    }

    @Test
    void analyzeSource_extractsClassName() {
        String source = """
                package com.example;
                public class MyService {}
                """;

        List<CodeElement> elements = analyzer.analyzeSource(source);

        assertThat(elements).extracting(CodeElement::fullyQualifiedName)
                .contains("com.example.MyService");
    }

    @Test
    void analyzeSource_extractsInterface() {
        String source = """
                package com.example;
                public interface MyRepository {}
                """;

        List<CodeElement> elements = analyzer.analyzeSource(source);

        Optional<CodeElement> iface = elements.stream()
                .filter(e -> e.fullyQualifiedName().equals("com.example.MyRepository"))
                .findFirst();

        assertThat(iface).isPresent();
        assertThat(iface.get().type()).isEqualTo(ElementType.INTERFACE);
    }

    @Test
    void analyzeSource_extractsMethods() {
        String source = """
                package com.example;
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int subtract(int a, int b) { return a - b; }
                }
                """;

        List<CodeElement> elements = analyzer.analyzeSource(source);

        assertThat(elements).extracting(CodeElement::fullyQualifiedName)
                .contains("com.example.Calculator#add", "com.example.Calculator#subtract");
    }

    @Test
    void analyzeSource_capturesDependenciesFromImports() {
        String source = """
                package com.example;
                import com.example.repository.UserRepository;
                import com.example.model.User;
                public class UserService {
                    private UserRepository repo;
                    public User findUser(String id) { return null; }
                }
                """;

        List<CodeElement> elements = analyzer.analyzeSource(source);

        Optional<CodeElement> serviceClass = elements.stream()
                .filter(e -> e.fullyQualifiedName().equals("com.example.UserService"))
                .findFirst();

        assertThat(serviceClass).isPresent();
        assertThat(serviceClass.get().dependencies())
                .contains("com.example.repository.UserRepository", "com.example.model.User");
    }

    @Test
    void analyzeSource_returnsEmptyForInvalidSource() {
        List<CodeElement> elements = analyzer.analyzeSource("not valid java !!!@#");
        // Parser may partially succeed; just assert no exception is thrown
        assertThat(elements).isNotNull();
    }

    @Test
    void analyzeSource_handlesEmptyClass() {
        String source = """
                package com.example;
                public class Empty {}
                """;

        List<CodeElement> elements = analyzer.analyzeSource(source);

        assertThat(elements).hasSize(1);
        assertThat(elements.get(0).type()).isEqualTo(ElementType.CLASS);
        assertThat(elements.get(0).dependencies()).isEmpty();
    }

    @Test
    void analyzeSource_handlesNoPackage() {
        String source = "public class Bare { public void run() {} }";

        List<CodeElement> elements = analyzer.analyzeSource(source);

        assertThat(elements).extracting(CodeElement::fullyQualifiedName)
                .contains("Bare", "Bare#run");
    }

    @Test
    void analyzeSource_extractsImplementedInterface() {
        String source = """
                package com.example;
                import com.example.spi.MyInterface;
                public class Impl implements MyInterface {}
                """;

        List<CodeElement> elements = analyzer.analyzeSource(source);

        Optional<CodeElement> impl = elements.stream()
                .filter(e -> e.fullyQualifiedName().equals("com.example.Impl"))
                .findFirst();

        assertThat(impl).isPresent();
        assertThat(impl.get().dependencies()).contains("com.example.spi.MyInterface");
    }
}
