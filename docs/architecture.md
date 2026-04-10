# Architecture

## Module dependency graph

```
rts-cli
  ├── rts-selector
  │     ├── rts-llm
  │     │     └── rts-core
  │     ├── rts-change
  │     │     ├── rts-analyzer
  │     │     │     └── rts-core
  │     │     └── rts-core
  │     └── rts-core
  ├── rts-analyzer
  └── rts-core
```

## Data flow — `rts select`

```
ProjectRoot
    │
    ▼
JavaAstAnalyzer          → List<CodeElement>
DependencyGraphBuilder   → DependencyGraph
TestMappingResolver      → List<TestCase>
    │
    ▼
JGitDiffParser           → List<ChangeInfo>
ChangeImpactAnalyzer     → Set<impactedClasses>
    │
    ▼
StaticSelector           → SelectionResult (STATIC)
    │
    ▼  (if LLM enabled)
LlmRefinementSelector    → SelectionResult (LLM_REFINED)
    │
    ▼
HybridSelector           → SelectionResult (HYBRID)
    │
    ▼
JSON output (stdout or file)
```

## Conservative safety invariant

The LLM refinement step can only **remove** tests from the candidate set produced by
the static layer, never add new ones. If the LLM response is invalid, unparseable,
or returns an error, the full static candidate set is returned unchanged.

## Dependency direction (edges mean "depends on")

- `rts-selector` → `rts-llm`, `rts-change`, `rts-core`
- `rts-change` → `rts-analyzer`, `rts-core`
- `rts-analyzer` → `rts-core`
- `rts-llm` → `rts-core`
- `rts-cli` → all modules
