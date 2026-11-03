/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.pastalab.fray.core.scheduler.POSScheduler
import org.pastalab.fray.junit.junit5.FrayTestExtension
import org.pastalab.fray.junit.junit5.annotations.FrayTest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference

/**
 * Fray controlled-scheduling variants for [AgenticEventListenerToolsStats].
 *
 * This listener is registered as a Spring singleton bean
 * (see `AgentPlatformConfiguration.toolsStats`) and receives process events
 * from every agent process on the platform, so in production its
 * `onProcessEvent` is called concurrently whenever processes run in parallel.
 * Fray systematically explores thread interleavings of that concurrent
 * recording instead of relying on lucky timing in a stress loop.
 *
 * These tests live in a dedicated `frayTest` source set: they are not part of
 * the default build or test run. See the Fray harness notes in the PR that
 * introduced this file for how to run them.
 */
@ExtendWith(FrayTestExtension::class)
class AgenticEventListenerToolsStatsFrayTest {

    /**
     * Minimal [AgentProcess] stand-in. Tool call events only read
     * `agentProcess.id` (for the default correlation id); the stats listener
     * itself reads only the event's tool name, result and running time.
     */
    private fun dummyAgentProcess(): AgentProcess {
        val self = AtomicReference<Any>()
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getId" -> "fray-dummy-process"
                "toString" -> "FrayDummyAgentProcess"
                "hashCode" -> 7
                "equals" -> self.get() === args?.firstOrNull()
                else -> defaultValueFor(method.returnType)
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

    private fun defaultValueFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        Void.TYPE -> null
        else -> null
    }

    private fun toolCallResponseEvent(
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
            llmOptions = LlmOptions.withModel("fray-test"),
        )
        return request.responseEvent(
            result = if (failure) {
                Result.failure(IllegalStateException("fray dummy tool failure"))
            } else {
                Result.success("ok")
            },
            runningTime = Duration.ofMillis(100),
        )
    }

    private fun publishConcurrently(
        listener: AgenticEventListenerToolsStats,
        process: AgentProcess,
        plan: List<Pair<String, Int>>,
    ): List<Thread> {
        val threads = plan.map { (tool, events) ->
            Thread {
                repeat(events) { i ->
                    listener.onProcessEvent(
                        toolCallResponseEvent(process, tool, failure = i % 5 == 0)
                    )
                    Thread.yield()
                }
            }
        }
        threads.forEach { it.start() }
        return threads
    }

    /**
     * Four "processes" (threads) record events for the same tool at the same
     * time, as happens on a platform running processes in parallel.
     * Every event must be counted exactly once: 4 x 25 events, every fifth
     * one a failure, must give calls=100 and failures=20.
     */
    @FrayTest(iterations = 1000)
    fun concurrentRecordingOfSameToolLosesNoCalls() {
        val listener = AgenticEventListenerToolsStats()
        val process = dummyAgentProcess()
        val threads = publishConcurrently(
            listener, process,
            listOf("toolA" to 25, "toolA" to 25, "toolA" to 25, "toolA" to 25),
        )
        threads.forEach { it.join() }

        val stats = listener.toolsStats["toolA"]
        assertNotNull(stats, "stats must exist for toolA")
        assertEquals(100, stats!!.calls, "every recorded event must be counted exactly once")
        assertEquals(20, stats.failures, "every failure must be counted exactly once")
        assertEquals(100L, stats.averageResponseTime, "all running times are 100ms, so the average must be exact")
    }

    /**
     * Two tools recorded concurrently from separate pairs of threads:
     * per-tool counts must stay exact and isolated.
     */
    @FrayTest(iterations = 500)
    fun concurrentRecordingAcrossToolsKeepsExactCounts() {
        val listener = AgenticEventListenerToolsStats()
        val process = dummyAgentProcess()
        val threads = publishConcurrently(
            listener, process,
            listOf("toolA" to 25, "toolB" to 25, "toolA" to 25, "toolB" to 25),
        )
        threads.forEach { it.join() }

        assertEquals(50, listener.toolsStats["toolA"]?.calls, "toolA calls")
        assertEquals(50, listener.toolsStats["toolB"]?.calls, "toolB calls")
        assertEquals(10, listener.toolsStats["toolA"]?.failures, "toolA failures")
        assertEquals(10, listener.toolsStats["toolB"]?.failures, "toolB failures")
    }

    /**
     * Eight threads released at the same instant by a barrier all record the
     * same tool, maximizing overlap inside the recording path. Uses the POS
     * scheduler, which prioritizes interleavings around shared-memory
     * accesses in weakly synchronized code.
     * 8 x 10 events, every fifth one a failure, must give calls=80 and
     * failures=16.
     */
    @FrayTest(iterations = 1000, scheduler = POSScheduler::class)
    fun concurrentRecordingReleasedTogetherLosesNoCalls() {
        val listener = AgenticEventListenerToolsStats()
        val process = dummyAgentProcess()
        val startGate = CyclicBarrier(8)
        val threads = (1..8).map {
            Thread {
                startGate.await()
                repeat(10) { i ->
                    listener.onProcessEvent(
                        toolCallResponseEvent(process, "toolA", failure = i % 5 == 0)
                    )
                    Thread.yield()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val stats = listener.toolsStats["toolA"]
        assertNotNull(stats, "stats must exist for toolA")
        assertEquals(80, stats!!.calls, "every recorded event must be counted exactly once")
        assertEquals(16, stats.failures, "every failure must be counted exactly once")
    }

    /**
     * A reader thread continuously snapshots `toolsStats` (which copies the
     * live map) while writer threads keep recording. Reading must never
     * throw, and once the writers finish the totals must be exact.
     */
    @FrayTest(iterations = 500)
    fun concurrentReadWhileRecordingDoesNotThrow() {
        val listener = AgenticEventListenerToolsStats()
        val process = dummyAgentProcess()
        val writersDone = AtomicReference(false)
        val readerFailure = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                while (!writersDone.get()) {
                    listener.toolsStats.forEach { (_, stats) ->
                        assertFalse(stats.calls < 0, "call count can never be negative")
                    }
                    Thread.yield()
                }
            } catch (t: Throwable) {
                readerFailure.set(t)
            }
        }
        val writers = publishConcurrently(
            listener, process,
            listOf("toolA" to 25, "toolA" to 25),
        )
        reader.start()
        writers.forEach { it.join() }
        writersDone.set(true)
        reader.join()

        if (readerFailure.get() != null) {
            throw AssertionError("Concurrent read of toolsStats threw", readerFailure.get())
        }
        assertEquals(50, listener.toolsStats["toolA"]?.calls, "toolA calls after writers finish")
    }
}
