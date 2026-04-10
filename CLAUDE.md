# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
./gradlew build          # compile all modules + run all tests
./gradlew test           # run tests only
./gradlew :rts-analyzer:test   # run a single module's tests
./gradlew :rts-cli:jar   # build the fat jar (rts-cli/build/libs/)
```

Run the CLI after building:
```bash
java -jar rts-cli/build/libs/rts-cli-1.0.0.jar analyze <project-path>
java -jar rts-cli/build/libs/rts-cli-1.0.0.jar select --project <path> --diff HEAD~1..HEAD
java -jar rts-cli/build/libs/rts-cli-1.0.0.jar select --project <path> --changed-files "src/Foo.java" --mode hybrid
```

## Module responsibilities

| Module | Role |
|--------|------|
| `rts-core` | Domain records + SPI interfaces. No business logic, no external deps. |
| `rts-analyzer` | JavaParser-based AST analysis → `CodeElement`, `DependencyGraph`, test discovery |
| `rts-change` | JGit-based diff parsing → `ChangeInfo`, transitive impact computation |
| `rts-selector` | Static and LLM-refined selection engines, `HybridSelector` orchestrator |
| `rts-llm` | OpenAI-compatible HTTP client, prompt templates, JSON response parser |
| `rts-cli` | Picocli entry point, config loading, output serialization |

## Architecture invariants

- **LLM can only remove tests, never add**: `LlmRefinementSelector` filters `StaticSelector` output. Any LLM failure falls back to the full static set.
- **No external dependencies in `rts-core`**: Only `jackson-databind` for config. SPI interfaces live here; implementations live in their respective modules.
- **`StaticSelector` works with zero LLM config**: `HybridSelector` with `llmEnabled=false` delegates entirely to `StaticSelector`.
- **Credentials always from environment**: `RtsConfig` resolves `${VAR_NAME}` and `${VAR:default}` patterns; never hard-code values.

## Code standards

- Use Java records for data objects (`CodeElement`, `TestCase`, `ChangeInfo`, `SelectionResult`).
- Return `Optional` or empty collections instead of `null`.
- Log via SLF4J (`LoggerFactory.getLogger(MyClass.class)`).
- `LlmClient` interface is in `rts-core/spi`; implementations are in `rts-llm`.
- No `null` in public API return types.
- Javadoc on all public classes and methods.

## Key implementation details

- **`DependencyGraph`** stores edges in both directions: `dependencies` (A→B) and `dependents` (reverse, B→A). The `getTransitiveDependents` BFS uses the `dependents` map for efficient impact propagation.
- **`JGitDiffParser`** uses JGit `CanonicalTreeParser`, not shell git. It delegates FQN resolution to `JavaAstAnalyzer`.
- **`LlmResponseParser`** strips markdown code fences and leading text before JSON parsing. Malformed individual `skippable_tests` entries are silently dropped (not a fatal error).
- **`RtsConfig`** supports both `rts-config.yaml` file-based config and env var overrides, with `${VAR:default}` syntax.

## Test fixtures

Fixture Java snippets for analyzer tests live in `rts-analyzer/src/test/resources/fixtures/`. Tests in `JavaAstAnalyzerTest` and `TestMappingResolverTest` use inline string literals for brevity; use resource files for longer/more complex fixtures.

## LLM configuration

Enable LLM in `rts-config.yaml` at the project root:
```yaml
llm:
  enabled: true
  endpoint: ${RTS_LLM_ENDPOINT}
  api-key: ${RTS_LLM_API_KEY}
  model: ${RTS_LLM_MODEL:gpt-4o-mini}
```

Then set env vars: `RTS_LLM_ENDPOINT`, `RTS_LLM_API_KEY`, `RTS_LLM_MODEL`.
