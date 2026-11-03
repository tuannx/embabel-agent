# vmlens concurrency tests

Data-race tests using [vmlens](https://vmlens.com) 1.2.28, which records
field accesses under a Java agent and checks their happens-before ordering
(the Java Memory Model view of thread safety).

These tests are **pass 2** of the concurrency harness for
`AgenticEventListenerToolsStats`, independent of the Fray harness in
`src/frayTest` (pass 1, fork PR #11). Fray controls the schedule at
synchronization points and judges *behavior*; vmlens judges *memory-model
legality* of the accesses on the traces it explores. The two agents never
run in the same JVM. Neither pass is part of the default Maven build or
test run, and this harness changes no production code.

## What is here

- `AgenticEventListenerToolsStatsVmlensTest` — the pilot scenario of
  2026-11-02, kept deliberately small: 2 threads x 3 tool-call response
  events for the same tool per interleaving, events built through the
  production path (`ToolCallRequestEvent(...).responseEvent(...)`), with a
  minimal dynamic-proxy `AgentProcess`. The listener under test is the
  platform-level Spring singleton from
  `AgentPlatformConfiguration.toolsStats`, so in production it receives
  events from every agent process concurrently while its `record()` path
  (`AgenticEventListenerToolsStats.kt:26,31-48`) has no synchronization.
- `VmlensPositiveControlTest` — the canonical unsynchronized `value++`
  counter. It is the trust condition for the whole suite (see below).

## Running

The harness is wired into `embabel-agent-api/pom.xml` behind the opt-in
Maven profile `vmlens` (JDK 21, the project's Java version). With the
profile off, the build is exactly as before: the `src/vmlensTest` sources
are not compiled and no vmlens dependency or agent is present.

```
mvn -Pvmlens -pl embabel-agent-api test
```

This restricts surefire to the `*VmlensTest` classes, forks them with
`-javaagent` pointing at the vmlens agent (installed into
`target/vmlens-agent` from `com.vmlens:standalone:1.2.28` earlier in the
build), and disables JaCoCo for the run so no second agent shares the JVM.

The race verdict lives in the HTML report, not in the test result. The
`verify` phase generates it into `target/reports/vmlens`:

```
mvn -Pvmlens -pl embabel-agent-api verify
# or, after a test run, directly:
java -jar target/vmlens/standalone-1.2.28.jar \
     -a target/vmlens-agent -r target/reports/vmlens report
```

## Expected results on the current production code

| Scenario | Test result | Report verdict |
|---|---|---|
| `VmlensPositiveControlTest` | passes, >1 interleavings | **Data race** — required; if absent, the whole run is unverified |
| `AgenticEventListenerToolsStatsVmlensTest` | passes (all 6 events counted in every explored interleaving) | **Data race** inside the `LinkedHashMap` backing `_stats` — the expected, correct flag for the current unsynchronized code |

The tests assert deterministic behavior only; they do **not** fail on the
report's data-race flag, and no production fix is smuggled in to make them
pass. After a production fix (e.g. `ConcurrentHashMap` + atomic `compute`
in `record()`), the ToolsStats scenario must go **Success** in the report
while the positive control must still flag — the two-way gate the
standalone pilot measured on 2026-11-02 (original: Data race, 2
interleavings, 41 report mentions; fixed variant: Success, 20
interleavings).

## Two operating rules (measured in the pilot, not folklore)

1. **Scenarios stay small.** Past vmlens's loop threshold, racing
   operations drop out of the trace and the report shows a false
   "Success". Do not "strengthen" these scenarios with big loops.
2. **No control flag, no verdict.** A "Success" report is only evidence
   if the positive control in the same run was flagged as a data race.
   Otherwise the run counts as unverified (warn), whatever the report
   says.
