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

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.test.unit.FakeOperationContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionPhaseTest {

    private class BarrierProvider(
        phases: Int,
    ) : DecisionProvider {
        override val isAvailable: Boolean = true
        private val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val calls = AtomicInteger()
        private val barrier = CyclicBarrier(phases)

        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                check(barrier.await(5, TimeUnit.SECONDS) >= 0)
                calls.incrementAndGet()
                return noulAnswers(state, questions)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class StaggeredProvider : DecisionProvider {
        override val isAvailable: Boolean = true

        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            val index = phaseIndex(state)
            Thread.sleep((5 - index) * 40L)
            return noulAnswers(state, questions)
        }
    }

    private class PartialProvider : DecisionProvider {
        override val isAvailable: Boolean = true
        val calls = AtomicInteger()

        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            calls.incrementAndGet()
            val index = phaseIndex(state)
            if (index == 2) {
                Thread.sleep(30)
                throw IllegalStateException("phase-2-failed")
            }
            return noulAnswers(state, questions)
        }
    }

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
                calls.incrementAndGet()
                return noulAnswers(state, questions)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `phases overlap and keep input order`() {
        val provider = BarrierProvider(phases = 4)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val answers = provider.evaluatePhases(phases(4), ExecutorAsyncer(executor), maxConcurrency = 4)
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), answers.map { it.noul("urgent").noul })
            assertEquals(4, provider.calls.get())
            assertEquals(4, provider.maxInFlight.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `slower earlier phases still return in input order`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val answers = StaggeredProvider().evaluatePhases(
                phases(4),
                ExecutorAsyncer(executor),
                maxConcurrency = 4,
            )
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), answers.map { it.noul("urgent").noul })
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
    fun `one phase failure fails the call after the others succeed`() {
        val provider = PartialProvider()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val failure = assertThrows<IllegalStateException> {
                provider.evaluatePhases(phases(3), ExecutorAsyncer(executor), maxConcurrency = 3)
            }
            assertEquals("phase-2-failed", failure.message)
            assertEquals(3, provider.calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `non positive maxConcurrency is rejected before work starts`() {
        val provider = CountingProvider()
        val asyncer = object : Asyncer {
            override fun <T> async(block: () -> T): CompletableFuture<T> =
                error("phases must not be scheduled")

            override fun <T, R> parallelMap(
                items: Collection<T>,
                maxConcurrency: Int,
                transform: (t: T) -> R,
            ): List<R> = error("phases must not be scheduled")
        }
        listOf(0, -1).forEach { maxConcurrency ->
            val failure = assertThrows<IllegalArgumentException> {
                provider.evaluatePhases(phases(1), asyncer, maxConcurrency)
            }
            assertTrue(failure.message!!.contains("positive"))
        }
        assertEquals(0, provider.calls.get())
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

    private companion object {
        fun phases(count: Int) = (1..count).map { index ->
            DecisionPhase(
                state = "s$index",
                questions = mapOf(
                    "urgent" to NoulQuestion("Is $index urgent?"),
                    "billing" to NoulQuestion("Is $index billing?"),
                ),
            )
        }

        fun phaseIndex(state: Any) = (state as String).removePrefix("s").toInt()

        fun noulAnswers(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ) = DecisionAnswers(
            questions.mapValues { NoulAnswer(phaseIndex(state).toDouble()) }
        )
    }
}
