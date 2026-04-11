package com.rts.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JacocoReportParserTest {

    private final JacocoReportParser parser = new JacocoReportParser();

    // ── findReport ────────────────────────────────────────────────────────────

    @Test
    void findReport_returnsEmpty_whenNoReportExists(@TempDir Path projectRoot) {
        assertThat(parser.findReport(projectRoot)).isEmpty();
    }

    @Test
    void findReport_detectsGradleReport(@TempDir Path projectRoot) throws Exception {
        Path reportFile = projectRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, minimalXml());

        Optional<Path> found = parser.findReport(projectRoot);
        assertThat(found).isPresent().hasValue(reportFile);
    }

    @Test
    void findReport_detectsMavenReport(@TempDir Path projectRoot) throws Exception {
        Path reportFile = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, minimalXml());

        Optional<Path> found = parser.findReport(projectRoot);
        assertThat(found).isPresent().hasValue(reportFile);
    }

    // ── parse ─────────────────────────────────────────────────────────────────

    @Test
    void parse_extractsCoveredMethodsOnly(@TempDir Path tmp) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="test">
                  <package name="com/example">
                    <class name="com/example/UserService" sourcefilename="UserService.java">
                      <method name="findById" desc="(J)Ljava/util/Optional;" line="20">
                        <counter type="INSTRUCTION" missed="0" covered="8"/>
                      </method>
                      <method name="delete" desc="(J)V" line="35">
                        <counter type="INSTRUCTION" missed="5" covered="0"/>
                      </method>
                      <method name="save" desc="(Lcom/example/User;)V" line="28">
                        <counter type="INSTRUCTION" missed="0" covered="3"/>
                      </method>
                    </class>
                  </package>
                </report>
                """;

        Path reportFile = tmp.resolve("jacoco.xml");
        Files.writeString(reportFile, xml);

        Map<String, Set<String>> coverage = parser.parse(reportFile);

        assertThat(coverage).containsKey("com.example.UserService");
        Set<String> methods = coverage.get("com.example.UserService");
        assertThat(methods).containsExactlyInAnyOrder("findById", "save");
        assertThat(methods).doesNotContain("delete");   // missed > 0, covered == 0
    }

    @Test
    void parse_handlesMultiplePackages(@TempDir Path tmp) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="test">
                  <package name="com/example/service">
                    <class name="com/example/service/OrderService" sourcefilename="OrderService.java">
                      <method name="placeOrder" desc="()V" line="10">
                        <counter type="INSTRUCTION" missed="0" covered="4"/>
                      </method>
                    </class>
                  </package>
                  <package name="com/example/model">
                    <class name="com/example/model/Order" sourcefilename="Order.java">
                      <method name="getTotal" desc="()D" line="5">
                        <counter type="INSTRUCTION" missed="0" covered="2"/>
                      </method>
                    </class>
                  </package>
                </report>
                """;

        Path reportFile = tmp.resolve("jacoco.xml");
        Files.writeString(reportFile, xml);

        Map<String, Set<String>> coverage = parser.parse(reportFile);

        assertThat(coverage).hasSize(2);
        assertThat(coverage.get("com.example.service.OrderService")).contains("placeOrder");
        assertThat(coverage.get("com.example.model.Order")).contains("getTotal");
    }

    @Test
    void parse_returnsEmptyMap_onCorruptFile(@TempDir Path tmp) throws Exception {
        Path reportFile = tmp.resolve("bad.xml");
        Files.writeString(reportFile, "NOT VALID XML <<<");

        Map<String, Set<String>> coverage = parser.parse(reportFile);
        assertThat(coverage).isEmpty();
    }

    // ── TestMappingResolver integration ───────────────────────────────────────

    @Test
    void extractTestCases_usesMethodLevelFqns_whenJacocoDataProvided() {
        String source = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import com.example.UserService;
                class UserServiceTest {
                    @Test void findById_returnsUser() {}
                }
                """;

        Map<String, Set<String>> jacocoCoverage = Map.of(
                "com.example.UserService", Set.of("findById", "save")
        );

        TestMappingResolver resolver = new TestMappingResolver();
        var tests = resolver.extractTestCases(source, jacocoCoverage);

        assertThat(tests).hasSize(1);
        Set<String> covered = tests.get(0).coveredElements();
        assertThat(covered).contains("com.example.UserService#findById", "com.example.UserService#save");
        // Must NOT contain bare class name when JaCoCo data is present and the class is found
        assertThat(covered).doesNotContain("com.example.UserService");
    }

    @Test
    void extractTestCases_fallsBackToHeuristic_whenJacocoCoverageIsEmpty() {
        String source = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import com.example.UserService;
                class UserServiceTest {
                    @Test void findById_returnsUser() {}
                }
                """;

        TestMappingResolver resolver = new TestMappingResolver();
        var tests = resolver.extractTestCases(source, Map.of());

        assertThat(tests).hasSize(1);
        Set<String> covered = tests.get(0).coveredElements();
        // Heuristic: class-level FQN
        assertThat(covered).contains("com.example.UserService");
        // No method-level FQNs
        assertThat(covered).noneMatch(e -> e.contains("#"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String minimalXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="test"><package name="x"><class name="x/A" sourcefilename="A.java"/></package></report>
                """;
    }
}
