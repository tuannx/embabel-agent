# Proposal: Unified Tool Contribution and Boundary-Only Naming

**Status**: Proposed. Design only; the code in this PR is a stop-gap (see section 8).
**Related**: #1834, #2093, #2095, #2132

## 0. Glossary

- **Leaf**: a concrete source of tools in its own module (RAG, Skills, MCP, code, ...).
- **Wire name**: the final string the LLM sees and returns in a tool call.
- **ToolNamePolicy**: how a leaf's tool names are combined with the leaf's own name to form wire
  names. `OwnerPrefixed`: `docs` + `search` -> `docs_search`. `OwnerNamed`: the tool is the entry point,
  so `memory` + `memory` -> `memory`. `AsDeclared`: names are already final.
- **Golden test**: a test that compares the full list of wire names per source against a
  checked-in file; any difference fails and must be reviewed on purpose.
- **Characterization test**: a test that records what the code does today, including quirks,
  so later refactors cannot change behavior unnoticed.

## 1. Summary

Tool names sent to the LLM are decided in at least six independent places. Each fix for a
naming bug (`docs_docs_*`, `memory_memory`, `p_p_x`, duplicate registration) adds another
string heuristic where the paths meet. This proposal introduces one owner for the naming
decision (`ToolCatalog`), makes tool sources *declare* how they are namespaced instead of
rewriting strings, and gates the work behind characterization tests that pin today's
LLM-visible names.

## 2. Context: how tools flow today

```mermaid
flowchart TD
    A["Tool, @LlmTool POJO<br/>ToolObject(objects, namingStrategy, filter)"] --> P
    B["LlmReference: ToolishRag, Skills, code, memory<br/>unprefixedTools() + namingStrategy"] --> P
    C["ToolGroup / ToolPublisher<br/>via ToolGroupResolver"] --> P
    D["MCP client / Spring AI ToolCallback<br/>external name strings"] --> P
    P["PromptRunner<br/>withTool(s) / withToolObject(s) / withReference(s) / withToolGroup(s)"] --> N
    N["Naming: no shared layer<br/>(six places rename tools)"] --> G
    G["safelyGetTools(): merge, distinct by name, sort"] --> L
    L["ToolLoop: availableTools, findTool(name)"] --> M["LLM"]
    X["McpToolExport (outbound)<br/>ToolObject/LlmReference -> MCP server<br/>namingStrategy applied twice"]
    A -.-> X
    B -.-> X
```

What each entry point supplies:

| Source | Enters as | Who decides the name |
|---|---|---|
| `Tool` / `@LlmTool` POJO | `ToolObject(objects, namingStrategy, filter)` or `withTool(s)` | a `StringTransformer` attached from outside |
| `LlmReference` (RAG, Skills, memory, code, web) | `unprefixedTools()` + `namingStrategy`; the legacy `tools()` may already be prefixed (`ToolishRag.tools()`) | the reference, via `toolPrefix()` |
| `UnfoldingTool`, `AgenticTool`, `PlaybookTool`, `StateMachineTool` | nested tools; inner tools appear after the parent is called | the parent tool |
| `ToolGroup` / `ToolPublisher` / `ToolConsumer` | resolved by role through `ToolGroupResolver` (`RegistryToolGroupResolver`) | nobody: tools keep their own names (audited, see below) |
| MCP client, Spring AI `ToolCallback` | external name strings | the remote server |
| `McpToolExport` (outbound) | `ToolObject` + `namingStrategy`, chained with a second strategy | two transformer layers |
| `PerGoalToolFactory` | generated from goals | the factory |

The function side is already direct: every source ends up as a `Tool` whose `call(input,
context)` runs the code. Identity is the weak part: it is only `definition.name: String`,
and namespacing is a string transformation applied afterwards (`RenamedTool` wraps a tool
only to change its name).

### ToolGroup path (audited)

`ToolConsumer.resolveTools` collects `consumer.tools` plus, for each `ToolGroupRequirement`
(identified by `role`), the tools of the group found by `ToolGroupResolver`. No renaming
happens on this path: group tools keep their own `definition.name`. Three name-based behaviors
matter for any change to naming:

- `requiredToolNames` is checked against the **raw** names of the resolved group
  (missing names throw). If the catalog renamed group tools, this check would fail.
- `ToolConsumer.resolveTools` runs its own `distinctBy { it.definition.name }.sortedBy {...}`.
  This is a second, independent dedup next to `safelyGetTools`, with no collision logging.
- `DefaultToolDecorator` calls `findToolGroupForTool(toolName = tool.definition.name)`, and
  `RegistryToolGroupResolver` implements it by scanning `tg.tools.any { name == toolName }`.
  It maps a tool back to its group by raw name; a renamed tool would not be found.

Conclusion: a group behaves like `ToolNamePolicy.AsDeclared`, but two other components depend on its raw
names, so the catalog must keep the original name available (for example `Tool` -> original
name + wire name) or those lookups must move to the catalog.

### How an LLM tool call is routed today

There is no name -> tool map. `DefaultToolLoop` keeps `availableTools: MutableList<Tool>`,
sends it with each request, and on a `ToolCall(id, name, args)` does
`tools.find { it.definition.name == name }` (`StreamingToolLoop` does the same). On a miss,
`ToolNotFoundPolicy` (default `AutoCorrectionPolicy`, Jaccard similarity over name tokens)
feeds a hint back to the model. `UnfoldingTool` has its own `associateBy` for shortcut
dispatch of inner tools. Consequences: the list can change between iterations (injected or
removed tools), and if two tools share a name `find` silently picks the first. Dedup happens
earlier and only by raw string (`distinctBy`).

## 3. Problem

1. **No owner for the final name.** Names are rewritten in `LlmReference`
   (`toolPrefix`/`namingStrategy`), `ToolObject.withPrefix`, `McpToolExport`,
   `ToolishRag.tools()`, `RenamedTool`, and `Skills.sanitizeToolName` (a second, independent
   copy of the sanitization rule).
2. **Two meanings for one method.** `tools()` may return prefixed names (backward
   compatibility) while `unprefixedTools()` returns bare names. The aggregator cannot tell
   which it received, so it guesses (`startsWith("${prefix}_")`).
3. **Intent is not declared.** A reference whose single tool *is* the entry point (`memory`)
   and a reference whose tools are members (`docs` + `search`) look the same to the
   aggregator.
4. **Names are rendered in several places** (LLM catalog, prompt text such as the script
   tool list in `Skills`, MCP export, logs) with no shared source of truth.
5. **No end-to-end check.** Tests cover single leaves; nothing pins the final names across
   all sources, so a naming change in one place is invisible in another.

### Why it grew this way (inferred from code and history)

Each source arrived later and reused the only mechanism available (`StringTransformer`).
Backward compatibility kept old methods alive (`toolInstances` -> `tools` ->
`unprefixedTools`). Fixes were applied at the symptom (#2093, #2132, this PR), and duplicate
name cases from #1834 were recorded as disabled tests rather than resolved. Strings are the
easiest type to extend, so nothing forced a source to state its intent.

## 4. Principle

Sources **declare** how their tools are namespaced. One component **decides** the wire name,
checks collisions, and serves lookups. Strings exist only at two boundaries: inbound (MCP,
Spring AI, annotations) and the LLM wire.

## 5. Design

```kotlin
// domain
/** How a source's tool names become wire names. Declared by the source, applied only by ToolCatalog. */
sealed interface ToolNamePolicy {
    /** Names are already final (MCP, Spring AI, ToolGroup): used exactly as given. */
    data object AsDeclared : ToolNamePolicy

    /** Each tool is named "<owner>_<tool>": owner "docs" + tool "search" -> docs_search. */
    data class OwnerPrefixed(val owner: String) : ToolNamePolicy

    /** The tool is the owner's entry point and is named exactly "<owner>": memory + memory -> memory. */
    data class OwnerNamed(val owner: String) : ToolNamePolicy

    /** Deprecated bridge for user-written StringTransformer strategies; removed after deprecation. */
    data class LegacyTransform(val transform: StringTransformer) : ToolNamePolicy
}

// port, implemented by every source
interface ToolSource {
    fun contribute(): ToolContribution
}

data class ToolContribution(
    val namePolicy: ToolNamePolicy,
    val tools: List<Tool>,            // simple names, not prefixed
    val children: List<ToolContribution> = emptyList(), // nested tools (unfolding)
    val promptNotes: String? = null,
)
```

`ToolCatalog` (application layer) is the single aggregation point:

- accepts contributions and resolves every wire name in one place; `ToolObject.filter` keeps
  seeing the simple name, applied before namespacing, as today;
- detects collisions on the resolved wire name and applies one policy (fail, or warn once
  per distinct pair), unwrapping delegating wrappers so messages name the real tools;
- exposes `byWireName: Map<String, Tool>` for routing and `nameFor(tool)` for prompt text;
- supports dynamic additions (tool-loop injection/removal) without rebuilding names by hand.

| Layer | Holds |
|---|---|
| Domain | `Tool`, `ToolNamePolicy` (later possibly `ToolId`) |
| Application | `ToolCatalog`: aggregation, namespacing decision, collision policy |
| Port | `ToolSource` (`ToolPublisher` is a close existing precedent) |
| Adapters | RAG, Skills, MCP, Spring AI, `UnfoldingReference` implement `ToolSource` |
| Wire boundary | sanitization, allowed characters, per-provider length limits |

`LlmReference` stops carrying sanitization in a default method. A plain reference maps to
`OwnerPrefixed`, `UnfoldingReference` to `OwnerNamed`, final-name sources to `AsDeclared`. This replaces the
idempotent guard in `namingStrategy` with explicit intent.

## 6. What this does and does not fix

- `memory_memory`, `docs_docs`: sources say `OwnerNamed`/`AsDeclared`; nothing is guessed.
- Duplicate registration and wrapper-identity false positives: dedup uses the resolved name.
- Prompt/catalog drift: both call `nameFor`.
- Not fixed by types alone: a tool named `docs_search` inside an `OwnerPrefixed("docs")` source still
  becomes `docs_docs_search`; sources must supply simple names.
- Joining with `_` is not injective (`(a_b, c)` and `(a, b_c)` both give `a_b_c`), so the
  collision check on wire names is required.

### Boundary rules

- Inbound names (MCP, Spring AI, `@LlmTool`) are parsed into a contribution once; outbound
  names are produced once by the catalog.
- The LLM returns a wire-name string. Routing uses `byWireName`; the formatter is not
  invertible once names are lowercased or sanitized, so never parse a wire name back.
- Display names are normalized through a factory (`"My API"` -> `my_api`); `require` guards
  only already-normalized values. Allowed characters and maximum length are provider
  configuration (OpenAI: `^[a-zA-Z0-9_-]{1,64}$`; MCP names may contain `.`), not core
  constants.
- Non-ASCII reference names collapse to underscores; two references can then share a prefix.
  The collision check must report it.

## 7. Risks and open items

- Public API surface: `ToolObject.namingStrategy`, `LlmReference.tools()` /
  `unprefixedTools()`, `McpToolExport`, Java callers. Needs adapters and a deprecation path.
- LLM-visible names are a de facto contract (prompts, tests, integrations depend on them), so
  no step may change them unnoticed.
- The `ToolGroup` path renames nothing but has name-based consumers (`requiredToolNames`,
  `findToolGroupForTool`, a separate `distinctBy`). The catalog must preserve the original
  name or take over those lookups (section 2, "ToolGroup path").
- Dynamic tools (unfolding, loop injection) require the catalog to support nested and
  incremental contributions.

## 8. Roadmap

0. **This PR (stop-gap)**: single-path registration, idempotent guard, whitespace-safe
   `toolPrefix()`, inner-tool renaming for unfolding, collision warning. Known gaps are listed
   in the PR description. These heuristics are meant to be removed by step 2.
1. **Characterization tests (gate for everything below).** Integration tests per leaf that
   pin final wire names, plus a golden file of `source -> wire names` where any diff must be
   reviewed. Pin current behavior, marking known quirks as such. Per leaf, at least:
   - plain name; name equal to the prefix; name starting with `prefix_`; mixed case;
   - reference names with spaces, punctuation, non-ASCII, empty/blank;
   - two sources producing the same wire name (collision policy);
   - `ToolObject.filter` combined with naming;
   - unfolding: outer and inner names, shortcut dispatch;
   - MCP export with chained naming strategies;
   - dynamic tools injected or removed by the tool loop;
   - round trip: the wire name the LLM would return reaches the right function;
   - `ToolGroup`: `requiredToolNames` still matches, `findToolGroupForTool` still finds the
     group, and the second `distinctBy` in `ToolConsumer.resolveTools` agrees with the first;
   - names rendered in prompt text equal the names in the catalog.
   Leaves: `Tool`/`@LlmTool` via `ToolObject`, `ToolishRag`, `Skills` (+ script tools), memory,
   code/file references, `UnfoldingReference`, agentic tools, MCP export, MCP client /
   Spring AI callbacks, `ToolGroup`, `PerGoalToolFactory`.
2. Add `ToolNamePolicy`, `ToolContribution`, `ToolCatalog` internally, used by
   `PromptRunner.withReference` and `safelyGetTools`. `Tool` and `LlmReference` unchanged; an
   adapter converts `LlmReference` and `ToolObject`. Done when the `startsWith` guard is
   deleted and the step-1 tests pass untouched.
3. Move leaves to declarations one PR at a time (`Skills`, `ToolishRag`, `McpToolExport`
   first); delete `Skills.sanitizeToolName`. `byWireName` replaces the linear `find` in
   `DefaultToolLoop` / `StreamingToolLoop`.
4. Optional: introduce `ToolId`; deprecate `StringTransformer`, `RenamedTool`,
   `ToolNamePolicy.LegacyTransform` and the `tools()` / `unprefixedTools()` split.

Open question for maintainers: is a catalog-level refactor (step 2) acceptable before any
change to the `Tool` interface?
