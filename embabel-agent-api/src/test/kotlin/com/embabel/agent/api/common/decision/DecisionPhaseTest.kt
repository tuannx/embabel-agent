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
package com.embabel.agent.api.common.decision

import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.test.unit.FakeOperationContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionPhaseTest {

    private class CountingProvider : DecisionProvider {
        override val isAvailable: Boolean = true
        private val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val calls = AtomicInteger()

        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                Thread.sleep(80)
                calls.incrementAndGet()
                return DecisionAnswers(
                    questions.mapValues { NoulAnswer((state as String).removePrefix("s").toDouble()) }
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun phases(count: Int) = (1..count).map { index ->
        DecisionPhase(
            state = "s$index",
            questions = mapOf(
                "urgent" to NoulQuestion("Is $index urgent?"),
                "billing" to NoulQuestion("Is $index billing?"),
            ),
        )
    }

    @Test
    fun `phases run concurrently and keep input order`() {
        val provider = CountingProvider()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val answers = provider.evaluatePhases(phases(4), ExecutorAsyncer(executor), maxConcurrency = 4)
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), answers.map { it.noul("urgent").noul })
            assertEquals(4, provider.calls.get())
            assertTrue(provider.maxInFlight.get() > 1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `maxConcurrency of one keeps phases serial`() {
        val provider = CountingProvider()
        val executor = Executors.newFixedThreadPool(4)
        try {
            provider.evaluatePhases(phases(3), ExecutorAsyncer(executor), maxConcurrency = 1)
            assertEquals(1, provider.maxInFlight.get())
            assertEquals(3, provider.calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `empty phases do not call the provider`() {
        val provider = CountingProvider()
        val executor = Executors.newFixedThreadPool(1)
        try {
            assertEquals(emptyList(), provider.evaluatePhases(emptyList(), ExecutorAsyncer(executor)))
            assertEquals(0, provider.calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `provider failure is not wrapped`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val failure = assertThrows<IllegalStateException> {
                DisabledDecisionProvider.evaluatePhases(
                    phases(2),
                    ExecutorAsyncer(executor),
                    maxConcurrency = 2,
                )
            }
            assertTrue(failure.message!!.contains("TYPESAFE_API_KEY"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `operation context runs phases through the platform asyncer`() {
        val provider = CountingProvider()
        val context = FakeOperationContext(decisionProvider = provider)
        val answers = context.decisionPhases(phases(2))
        assertEquals(listOf(1.0, 2.0), answers.map { it.noul("billing").noul })
        assertEquals(2, provider.calls.get())
    }
}
