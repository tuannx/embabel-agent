# Proposal: Unified Tool Contribution & Boundary-Only Naming

**Status**: Proposed (design only; the code in this PR is the stop-gap, see section 6)
**Related**: #2093, #2095, #2132

## 1. Problem

Every tool source decides tool names its own way, so nothing keeps them in sync:

| Source | Enters as | Who decides the name |
|---|---|---|
| `Tool` / `@LlmTool` POJO | `ToolObject(objects, namingStrategy, filter)` | transformer attached from outside |
| `LlmReference` (RAG, Skills, memory, code) | `unprefixedTools()` + `namingStrategy`; legacy `tools()` may already be prefixed | the reference (`toolPrefix()`) |
| `UnfoldingTool`, `AgenticTool`, `PlaybookTool`, `StateMachineTool` | nested tools, inner tools visible after unfolding | the parent tool |
| MCP export (`McpToolExport`) | `ToolObject` + `namingStrategy` applied twice | two transformer layers |
| MCP client / Spring AI `ToolCallback` | external name strings | the remote server |
| `PerGoalToolFactory` | generated from goals | the factory |

At least six places manipulate name strings (`LlmReference.toolPrefix/namingStrategy`,
`ToolObject.withPrefix`, `McpToolExport`, `ToolishRag.tools()`, `RenamedTool`,
`Skills.sanitizeToolName`). The bugs `docs_docs_*`, `memory_memory`, `p_p_x` arise where these
meet: the aggregator cannot tell whether a name is already final, so each fix adds another
string heuristic (`startsWith("${prefix}_")`).

The function side is already direct (`Tool.call`). What is missing is declared intent about
naming, and one place that owns it.

## 2. Principle

Sources **declare** how their tools are named; one component **decides** the wire name. No
string guessing in the core, no string rewriting by individual sources.

## 3. Design

```kotlin
// domain
sealed interface Namespacing {
    data object None : Namespacing                    // names are already final
    data class Prefix(val ns: String) : Namespacing   // docs + search -> docs_search
    data class Collapse(val ns: String) : Namespacing // the tool is the entry point: memory -> memory
}

// port, implemented by every source
interface ToolSource {
    fun contribute(): ToolContribution
}

data class ToolContribution(
    val namespacing: Namespacing,
    val tools: List<Tool>,          // simple names, not prefixed
    val promptNotes: String? = null,
)
```

`ToolCatalog` (application layer) is the single aggregation point:

- accepts `ToolContribution`s and resolves each tool's wire name in one place;
- detects collisions on the **resolved wire name** (not on raw names, not by wrapper identity)
  and applies one policy: fail or warn once per distinct pair;
- exposes `byWireName: Map<String, Tool>` (used to route the LLM's tool call back) and
  `nameFor(tool)` (used by prompt text, e.g. `Skills` script-tool lists), so prompt and
  catalog share a single source of truth.

Layering:

| Layer | Holds |
|---|---|
| Domain | `Tool`, `Namespacing` (later possibly `ToolId`) |
| Application | `ToolCatalog`: aggregation, namespacing decision, collision policy |
| Port | `ToolSource` |
| Adapters | RAG, Skills, MCP, Spring AI, `UnfoldingReference` implement `ToolSource` |
| Wire boundary | sanitization, allowed characters, per-provider length limits |

`LlmReference` stops carrying sanitization rules in a default method. A plain reference maps to
`Prefix`, `UnfoldingReference` to `Collapse`, final-name sources to `None`. This replaces the
idempotent guard in `namingStrategy` with explicit intent.

## 4. What this does and does not fix

- `memory_memory`, `docs_docs`: sources say `Collapse`/`None`; the catalog never guesses.
- Duplicate registration and wrapper-identity false positives: dedup is by resolved name.
- Prompt/catalog drift: both call `nameFor`.
- Not fixed by types alone: a tool named `docs_search` inside a `Prefix("docs")` source still
  becomes `docs_docs_search`. Sources must supply simple names. Joining with `_` is not
  injective (`(a_b, c)` and `(a, b_c)` both give `a_b_c`), so the collision check on wire
  names is required, not optional.

## 5. Boundary rules

- Strings still exist at two boundaries: inbound (MCP, Spring AI, `@LlmTool` names) and
  outbound (the LLM wire). Inbound names are parsed into a contribution once; outbound names
  are produced once by the catalog.
- The LLM returns a wire-name string. Routing needs the `byWireName` map; the formatter is
  not invertible once names are lowercased/sanitized, so never `parse` a wire name.
- Sanitization normalizes display names (`"My API"` -> `my_api`) through a factory; `require`
  is only for already-normalized values. Allowed characters and max length (OpenAI:
  `^[a-zA-Z0-9_-]{1,64}$`; MCP names may contain `.`) are provider configuration at the
  boundary, not constants in the core.
- Non-ASCII names collapse to underscores; two references can then share a prefix. The
  collision check must report this.

## 6. Roadmap

0. **This PR (stop-gap)**: single-path registration, idempotent guard, whitespace-safe
   `toolPrefix()`, inner-tool renaming for unfolding, collision warning. Known gaps are listed
   in the PR description. These heuristics are meant to be removed by step 2.
1. **Characterization tests (gate for everything below).** Tool names sent to the LLM are a
   public contract (prompts, tests and integrations depend on them), so before any refactor
   add integration tests per leaf that pin the final wire names, plus a golden file of
   `source -> wire names` where any diff must be reviewed. Pin current behavior, including
   known quirks (mark them as such), so a change is always intentional. Per leaf, at least:
   - plain name; name equal to the prefix; name starting with `prefix_`; mixed case;
   - reference names with spaces, punctuation, non-ASCII, empty/blank;
   - two sources producing the same wire name (collision policy);
   - `ToolObject.filter` combined with naming (filter sees the simple name, then naming);
   - unfolding: outer and inner names, shortcut dispatch;
   - MCP export with chained naming strategies;
   - dynamic tools injected/removed by the tool loop;
   - round trip: the wire name the LLM would return reaches the right function;
   - names rendered in prompt text equal the names in the catalog.
   Leaves covered: `Tool`/`@LlmTool` via `ToolObject`, `ToolishRag`, `Skills` (+ script tools),
   DICE memory, code/file references, `UnfoldingReference`, agentic tools, MCP export,
   MCP client / Spring AI callbacks, `PerGoalToolFactory`.
2. Add `Namespacing`, `ToolContribution`, `ToolCatalog` internally, used by
   `PromptRunner.withReference` and `safelyGetTools`. `Tool` and `LlmReference` unchanged; an
   adapter turns `LlmReference` and `ToolObject` into contributions. Add
   `Namespacing.Custom(StringTransformer)` (deprecated) for user-written naming strategies.
   Done when the `startsWith` guard is deleted and the step-1 tests still pass untouched.
3. Move leaves to declarations one PR at a time (`Skills`, `ToolishRag`, `McpToolExport`
   first); delete `Skills.sanitizeToolName`. The catalog must support nested contributions
   (unfolding) and dynamic additions (tool loop injection); `byWireName` replaces the linear
   `find` in `DefaultToolLoop`/`StreamingToolLoop`.
4. Optional: `ToolId`; deprecate `StringTransformer`, `RenamedTool`, `Namespacing.Custom`
   and the `tools()` / `unprefixedTools()` split. Public API (`ToolObject.namingStrategy`,
   `LlmReference`, Java callers) needs a deprecation path.

Open question for maintainers: is a catalog-level refactor (step 2) acceptable before any
`Tool` interface change?
