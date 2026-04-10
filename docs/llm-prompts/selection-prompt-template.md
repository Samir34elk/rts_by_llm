# LLM Selection Prompt Template

The full prompt is assembled by `PromptTemplates#buildSelectionPrompt`. This document
explains the structure and rationale.

## Structure

```
[System instruction — rules and constraints]

## Code Changes
- MODIFIED: `com.example.Foo` (`src/main/java/com/example/Foo.java`)
  - Changed methods: `doSomething`, `validate`

## Candidate Tests (selected by static analysis)
- `com.example.FooTest#testDoSomething`
  - Covers: `com.example.Foo`, `com.example.Bar`

## Task
[Instructions to return JSON matching response-schema.json]
```

## Design decisions

- The LLM is told to identify tests to **skip**, not tests to run. This creates a
  conservative bias: any ambiguity defaults to running the test.
- Confidence < 0 or > 1 causes the parser to reject the response entirely, falling
  back to the full candidate set.
- Markdown code fences (` ```json `) in the response are stripped before parsing.
- A test entry missing any required field (`test_class`, `test_method`, `reason`) is
  silently skipped rather than failing the whole response.
