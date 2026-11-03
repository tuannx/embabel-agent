# Fray concurrency tests

Controlled-concurrency tests using [Fray](https://github.com/cmu-pasta/fray)
(CMU Pasta Lab), which systematically explores thread interleavings instead of
relying on lucky timing in stress loops, and records a replayable schedule
when it finds a failure.

These tests live in a dedicated `frayTest` source set. They are **not** part
of the default Maven build or test run, and adding them changes no production
code and no build configuration.

## What is here

`AgenticEventListenerToolsStatsFrayTest` exercises
`AgenticEventListenerToolsStats` — the tool-statistics event listener that is
registered as a platform-level Spring singleton bean
(`AgentPlatformConfiguration.toolsStats`) and therefore receives process
events from every agent process on the platform concurrently. The scenarios
publish tool-call response events from several threads at once and assert
that every event is counted exactly once, including while another thread
reads the statistics.

## Running

Fray 0.9.2 needs a JDK 25 runtime that it instruments itself. With Maven this
is normally driven by the Fray Gradle/Maven tooling in a dedicated module.
The runs that verified this harness in a sandbox without a working
Gradle/Maven daemon used the manual equivalent of the Fray Gradle plugin
recipe:

1. Compile the module's main sources and this test with Kotlin 2.2.21
   (the project's Kotlin version) against the module's dependency jars.
2. Build Fray's instrumented JDK with `jlink` using
   `fray-instrumentation-jdk-0.9.2.jar`, and unpack the JVMTI agent from
   `fray-jvmti-linux-x8664-0.9.2.jar`.
3. Run the JUnit Platform Console with the instrumented JDK:

```
java -agentpath:<path-to>/libjvmti.so \
     -javaagent:<path-to>/fray-instrumentation-agent-0.9.2.jar \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.io=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     -Dfray.workDir=<work-dir> -Dfray.organize.by.test=true \
     -cp <junit-console:test-classes:classes:dependency-jars> \
     org.junit.platform.console.ConsoleLauncher \
     --select-class com.embabel.agent.spi.support.AgenticEventListenerToolsStatsFrayTest
```

Fray writes one `fray.log` per test method under `fray.workDir`, and a
replay recording for any failing schedule.

## Verified results at the time of writing

- Random scheduler: 1,000 iterations (176 covered timelines) on the
  same-tool scenario, 500 iterations (125 timelines) on the two-tool
  scenario, 500 iterations (32 timelines) on the read-while-recording
  scenario — no failure found.
- POS scheduler: 1,000 iterations (1,000 covered timelines) on the
  barrier-released same-tool scenario — no failure found.
- A plain (non-Fray) high-contention stress of the same class on a normal
  JVM — 8 threads, 400,000 events for one tool — loses between ~33% and
  ~100% of the counts in every trial. The race the Fray scenarios assert
  against is real; Fray's schedulers do not expose it here because the
  recording path's critical section contains no synchronization operation
  for Fray to schedule around. See the PR description for the full data.
