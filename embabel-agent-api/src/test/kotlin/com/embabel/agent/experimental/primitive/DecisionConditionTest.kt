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
package com.embabel.agent.experimental.primitive

import com.embabel.agent.api.common.decision.DecisionAnswers
import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DecisionQuestion
import com.embabel.agent.api.common.decision.DisabledDecisionProvider
import com.embabel.agent.api.common.decision.NoulAnswer
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionConditionTest {

    private class FixedNoulProvider(
        private val probability: Double,
    ) : DecisionProvider {
        override val isAvailable: Boolean = true
        var evaluations: Int = 0
        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            evaluations++
            return DecisionAnswers(mapOf("question" to NoulAnswer(probability)))
        }
    }

    private fun condition(
        provider: DecisionProvider,
        threshold: Double = 0.6,
        state: Any = "ticket text",
    ): Pair<DecisionCondition, FakeOperationContext> {
        val context = FakeOperationContext(decisionProvider = provider)
        return DecisionCondition(
            name = "urgent",
            state = { state },
            instructions = "Is this urgent?",
            threshold = threshold,
        ) to context
    }

    @Nested
    inner class Thresholds {

        @Test
        fun `above threshold is true`() {
            val (condition, context) = condition(FixedNoulProvider(0.92))
            assertEquals(ConditionDetermination.TRUE, condition.evaluate(context))
        }

        @Test
        fun `below threshold is false`() {
            val (condition, context) = condition(FixedNoulProvider(0.2))
            assertEquals(ConditionDetermination.FALSE, condition.evaluate(context))
        }

        @Test
        fun `at threshold is true`() {
            val (condition, context) = condition(FixedNoulProvider(0.6), threshold = 0.6)
            assertEquals(ConditionDetermination.TRUE, condition.evaluate(context))
        }

        @Test
        fun `custom threshold applies`() {
            val (condition, context) = condition(FixedNoulProvider(0.7), threshold = 0.8)
            assertEquals(ConditionDetermination.FALSE, condition.evaluate(context))
        }
    }

    @Nested
    inner class Availability {

        @Test
        fun `unknown without a decision backend`() {
            val (condition, context) = condition(DisabledDecisionProvider)
            assertEquals(ConditionDetermination.UNKNOWN, condition.evaluate(context))
        }

        @Test
        fun `unavailable backend is not called`() {
            val provider = FixedNoulProvider(0.9)
            val unavailable = object : DecisionProvider by provider {
                override val isAvailable: Boolean = false
            }
            val (condition, context) = condition(unavailable)
            assertEquals(ConditionDetermination.UNKNOWN, condition.evaluate(context))
            assertEquals(0, provider.evaluations)
        }

        @Test
        fun `backend failures propagate`() {
            val failing = object : DecisionProvider {
                override val isAvailable: Boolean = true
                override fun evaluate(
                    state: Any,
                    questions: Map<String, DecisionQuestion>,
                ): DecisionAnswers = throw IllegalStateException("overloaded")
            }
            val (condition, context) = condition(failing)
            assertThrows<IllegalStateException> { condition.evaluate(context) }
        }
    }

    @Nested
    inner class Cost {

        @Test
        fun `cheaper than a prompt condition`() {
            val (condition, _) = condition(FixedNoulProvider(0.9))
            assertTrue(condition.cost < 1.0)
        }
    }
}
