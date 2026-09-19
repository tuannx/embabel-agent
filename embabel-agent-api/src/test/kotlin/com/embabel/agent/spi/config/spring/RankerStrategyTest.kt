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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DisabledDecisionProvider
import com.embabel.agent.api.common.ranking.RankingStrategy
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.support.DecisionRanker
import com.embabel.agent.spi.support.LlmRanker
import com.embabel.agent.spi.support.RankingProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.assertIs

class RankerStrategyTest {

    private val configuration = AgentPlatformConfiguration()

    private val llmOperations = mockk<LlmOperations>()

    private fun availableProvider(): DecisionProvider =
        mockk { every { isAvailable } returns true }

    private fun providers(vararg providers: DecisionProvider): ObjectProvider<DecisionProvider> {
        val provider = mockk<ObjectProvider<DecisionProvider>>()
        every { provider.getIfAvailable() } returns providers.firstOrNull()
        return provider
    }

    private fun ranker(
        strategy: RankingStrategy,
        decisionProvider: ObjectProvider<DecisionProvider>,
    ) = configuration.ranker(
        llmOperations = llmOperations,
        rankingProperties = RankingProperties(strategy = strategy),
        decisionProvider = decisionProvider,
    )

    @Nested
    inner class Auto {

        @Test
        fun `available provider wins`() {
            assertIs<DecisionRanker>(
                ranker(RankingStrategy.AUTO, providers(availableProvider()))
            )
        }

        @Test
        fun `no provider keeps the llm ranker`() {
            assertIs<LlmRanker>(ranker(RankingStrategy.AUTO, providers()))
        }

        @Test
        fun `disabled provider keeps the llm ranker`() {
            assertIs<LlmRanker>(ranker(RankingStrategy.AUTO, providers(DisabledDecisionProvider)))
        }
    }

    @Nested
    inner class Jev {

        @Test
        fun `available provider wins`() {
            assertIs<DecisionRanker>(
                ranker(RankingStrategy.JEV, providers(availableProvider()))
            )
        }

        @Test
        fun `missing provider fails fast`() {
            val failure = assertThrows<IllegalStateException> {
                ranker(RankingStrategy.JEV, providers())
            }
            assert(failure.message!!.contains("TYPESAFE_API_KEY"))
        }
    }

    @Nested
    inner class Llm {

        @Test
        fun `provider is ignored`() {
            assertIs<LlmRanker>(
                ranker(RankingStrategy.LLM, providers(availableProvider()))
            )
        }
    }
}
