# 0001. Decision provider SPI

## Status

Accepted

## Context

Embabel ranks agents and goals with `LlmRanker`, evaluates `PromptCondition` by asking an LLM to create an object, and exposes generation through `OperationContext` and `PromptRunner`. Those paths exist to produce language. TypeSafe Jev answers a different kind of question: a yes/no probability (`noul`), a choice over a fixed set, or a score on an ordered rubric. One POST to `/v1/systemone` returns calibrated values. It does not generate text, call tools, or plan.

Using an LLM for those bounded judgments pays for token generation, parsing, and long timeouts in order to obtain a number the platform already knows how to threshold.

## Scored options

1. **Adapt Jev as an LLM.** Implement it as `LlmMessageSender`, a Spring AI `ChatModel`, or a fake chat-completions endpoint. Rejected. Ranking, conditions, `generateText`, `createObject`, tool loops, and planning would all start calling a service that cannot do that work. Timeouts, usage events, and failures would be reported as if a model had generated text.

2. **Call the HTTP API from each call site.** Have `Autonomy`, `PromptCondition`, and action code post to TypeSafe themselves. Rejected. Client setup, the API key, timeouts, and failure policy would be copied into every site, and there would be no single way to turn the capability off.

3. **Add an optional `DecisionProvider` SPI and wire it only into bounded judgments.** Selected. The provider sits next to the existing platform SPIs. `DecisionRanker` uses it for ranking and fails open to `LlmRanker`. `DecisionCondition` uses it for yes/no gates. `OperationContext.decisions()` exposes `noul`, `choice`, and `score` to `@Action` and `@Condition` code. No key means a disabled provider and the previous behavior.

## Decision

`DecisionProvider` is the SPI for bounded, non-generative judgments over caller-supplied state. The TypeSafe HTTP client, autoconfiguration, and starter are the optional implementation. The default model is `jev-latest`. Timeouts stay short. `DecisionUsageEvent` records model, question ids, token counts, and latency. State and API keys are not logged. Ranking strategy `auto` uses the provider when it is available, `jev` requires it, and `llm` keeps the LLM ranker.

## Trade-off accepted

With strategy `auto`, a Jev failure during ranking falls through to the LLM ranker, so an outage is slower and less visible than a hard error. Strategy `jev` is the fail-fast alternative. The SPI does not decide tool execution, RAG reranking, model routing, or planning. Callers choose the state they send; the client does not redact it, and it does not put that state in logs or exception messages.

## Revisit trigger

Revisit this when a judgment must fail closed instead of falling through to an LLM (tool invocation, policy block), when a second decision backend needs a registry instead of the single `PlatformServices` hook, or when the TypeSafe wire contract stops matching `noul`, `choice`, and `score`.
