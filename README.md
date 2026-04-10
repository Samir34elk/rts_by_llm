# RTS — Regression Test Selection for Java

Selects the minimal subset of tests to re-run given a Git diff, using static dependency analysis with optional LLM refinement.

## Quick start

```bash
./gradlew build
java -jar rts-cli/build/libs/rts-cli-1.0.0.jar select \
  --project /path/to/your/project \
  --diff HEAD~1..HEAD
```

Output is a JSON object consumable by CI pipelines:
```json
{
  "source": "STATIC",
  "selectedTestCount": 3,
  "selectedTests": [
    { "class": "com.example.FooTest", "method": "testSomething" }
  ],
  "detectedChanges": [...],
  "reasoning": {...}
}
```

## Commands

```bash
# Analyze a project (inspect dependency graph + test discovery)
rts analyze <project-path> [--output graph.json]

# Select tests from a git diff range
rts select --project <path> --diff HEAD~1..HEAD [--mode static|hybrid]

# Select tests from an explicit file list
rts select --project <path> --changed-files "src/Foo.java,src/Bar.java" [--mode hybrid]
```

## How it works

1. **Static analysis** — JavaParser builds a class dependency graph. `StaticSelector` finds all tests whose covered classes transitively depend on any changed class.
2. **LLM refinement** (optional) — An LLM reviews the candidate set and identifies tests that can be safely skipped. The LLM can only *remove* tests, never add them, ensuring a conservative safety invariant.

## LLM configuration

Create `rts-config.yaml` at the project root:
```yaml
llm:
  enabled: true
  endpoint: ${RTS_LLM_ENDPOINT}
  api-key: ${RTS_LLM_API_KEY}
  model: ${RTS_LLM_MODEL:gpt-4o-mini}
  max-tokens: 2000
  temperature: 0.1
```

Set environment variables: `RTS_LLM_ENDPOINT`, `RTS_LLM_API_KEY`, `RTS_LLM_MODEL`.
Works with any OpenAI-compatible endpoint.

## GitHub Actions integration

```yaml
- name: Select impacted tests
  run: |
    java -jar rts-cli.jar select --project . --diff ${{ github.event.before }}..${{ github.sha }} \
      --output rts-result.json
- name: Run selected tests
  run: |
    # Parse rts-result.json to build a test filter expression for your build tool
```
