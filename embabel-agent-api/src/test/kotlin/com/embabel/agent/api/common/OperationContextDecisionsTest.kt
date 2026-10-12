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
package com.embabel.agent.api.common

import com.embabel.agent.api.common.decision.ChoiceAnswer
import com.embabel.agent.api.common.decision.DecisionAnswers
import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DecisionQuestion
import com.embabel.agent.api.common.decision.DisabledDecisionProvider
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.unit.FakeOperationContext
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OperationContextDecisionsTest {

    private class StaticDecisionProvider : DecisionProvider {
        override val isAvailable: Boolean = true
        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers = DecisionAnswers(
            mapOf("question" to ChoiceAnswer("a", mapOf("a" to 1.0), 1.0))
        )
    }

    @Nested
    inner class Resolution {

        @Test
        fun `disabled without a configured backend`() {
            val context = FakeOperationContext()
            assertFalse(context.decisions().isAvailable)
        }

        @Test
        fun `fake provider is returned when configured`() {
            val provider = StaticDecisionProvider()
            val context = FakeOperationContext(decisionProvider = provider)
            assertSame(provider, context.decisions())
        }

        @Test
        fun `platform services provider is returned`() {
            val provider = StaticDecisionProvider()
            val services = IntegrationTestUtils.dummyPlatformServices(decisionProvider = provider)
            val process = IntegrationTestUtils.dummyAgentProcessRunning(
                agent = com.embabel.agent.test.unit.DummyAgent,
                platformServices = services,
            )
            val context = FakeOperationContext(
                processContext = com.embabel.agent.core.ProcessContext(
                    platformServices = services,
                    agentProcess = process,
                ),
            )
            assertSame(provider, context.decisions())
        }

        @Test
        fun `decisions can answer from action code`() {
            val context = FakeOperationContext(decisionProvider = StaticDecisionProvider())
            val decisions = context.decisions()
            assertTrue(decisions.isAvailable)
            assertTrue(decisions !== DisabledDecisionProvider)
        }
    }
}
