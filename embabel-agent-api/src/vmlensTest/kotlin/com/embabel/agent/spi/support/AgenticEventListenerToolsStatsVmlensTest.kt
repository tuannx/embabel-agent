/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.spi.support

import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.common.ai.model.LlmOptions
import com.vmlens.api.AllInterleavings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * vmlens (happens-before data-race) scenario for [AgenticEventListenerToolsStats].
 *
 * This is pass 2 of the concurrency harness for this class, independent of the
 * Fray pass in `src/frayTest`: Fray controls the schedule at synchronization
 * points and judges behavior, vmlens records field accesses and judges their
 * happens-before ordering. The two agents never run in the same JVM.
 *
 * The scenario is deliberately small — 2 threads x 3 events for the same tool
 * per interleaving, events built through the same production path as the
 * plain-JVM stress (`ToolCallRequestEvent(...).responseEvent(...)`). Larger
 * scenarios are not more thorough with vmlens: past its loop threshold the
 * racing operations drop out of the trace and the report shows a false
 * "Success" (measured in the standalone pilot, 2026-11-02). That is also why
 * [VmlensPositiveControlTest] lives in the same suite: a "Success" report is
 * only trustworthy if the control is flagged in the same run.
 *
 * Expected result on the current production code: this test passes
 * behaviorally (in every interleaving vmlens explores, all 6 events are
 * counted — the pilot measured the same), but the vmlens **report** flags a
 * data race inside the `LinkedHashMap` backing `_stats`
 * (`AgenticEventListenerToolsStats.kt:26`, `record()` lines 31-48). That flag
 * is the correct, expected verdict for the harness: the listener is a Spring
 * singleton bean (`AgentPlatformConfiguration.toolsStats`, line 218) receiving
 * events from every process on the platform, and its recording path has no
 * synchronization at all. The race verdict lives in the report generated under
 * `target/reports/vmlens`; see `src/vmlensTest/README.md`. Once the production
 * class is fixed (e.g. `ConcurrentHashMap` + atomic `compute`), this same
 * scenario must go silent in the report — the pilot measured Success with 20
 * interleavings on such a variant.
 *
 * These tests live in a dedicated `vmlensTest` source set behind the opt-in
 * Maven profile `vmlens`. They are not part of the default build or test run.
 */
class AgenticEventListenerToolsStatsVmlensTest {

    /**
     * Minimal [AgentProcess] stand-in. Tool call events only read
     * `agentProcess.id` (for the default correlation id); the stats listener
     * itself reads only the event's tool name, result and running time.
     */
    private fun dummyAgentProcess(): AgentProcess {
        val self = AtomicReference<Any>()
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getId" -> "vmlens-dummy-process"
                "toString" -> "VmlensDummyAgentProcess"
                "hashCode" -> 7
                "equals" -> self.get() === args?.firstOrNull()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
        val proxy = Proxy.newProxyInstance(
            AgentProcess::class.java.classLoader,
            arrayOf(AgentProcess::class.java),
            handler,
        )
        self.set(proxy)
        return proxy as AgentProcess
    }

    private fun makeEvent(
        process: AgentProcess,
        tool: String,
        failure: Boolean,
    ): ToolCallResponseEvent {
        val request = ToolCallRequestEvent(
            agentProcess = process,
            action = null,
            tool = tool,
            toolGroupMetadata = null,
            toolInput = "{}",
            llmOptions = LlmOptions.withModel("vmlens"),
        )
        return request.responseEvent(
            result = if (failure) Result.failure(IllegalStateException("x")) else Result.success("ok"),
            runningTime = Duration.ofMillis(100),
        )
    }

    @Test
    fun concurrentRecordingFromTwoThreadsExploresInterleavings() {
        val process = dummyAgentProcess()
        var iterations = 0
        val all = AllInterleavings("embabel-toolsstats")
        try {
            while (all.hasNext()) {
                iterations++
                val listener = AgenticEventListenerToolsStats()
                val thrown = AtomicReference<Throwable>()
                val workers = (1..2).map {
                    Thread {
                        try {
                            repeat(3) { i ->
                                listener.onProcessEvent(makeEvent(process, "toolA", failure = i % 5 == 0))
                            }
                        } catch (t: Throwable) {
                            thrown.compareAndSet(null, t)
                        }
                    }
                }
                workers.forEach { it.start() }
                workers.forEach { it.join() }
                assertNull(thrown.get(), "recording must not throw in any interleaving")
                val stats = listener.toolsStats["toolA"]
                assertNotNull(stats, "toolA must be recorded in every interleaving")
                assertEquals(6, stats!!.calls, "all 6 events must be counted in every interleaving")
                assertEquals(2, stats.failures, "one failure per thread must be counted in every interleaving")
            }
        } finally {
            all.close()
        }
        println("TOOLSTATS iterations=$iterations")
        assertTrue(iterations > 1, "vmlens must explore more than one interleaving, explored $iterations")
    }
}
