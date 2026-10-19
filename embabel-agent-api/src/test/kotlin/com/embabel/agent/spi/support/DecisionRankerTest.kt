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

import com.embabel.agent.api.common.decision.ChoiceAnswer
import com.embabel.agent.api.common.decision.DecisionAnswers
import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DecisionQuestion
import com.embabel.agent.api.common.ranking.Ranker
import com.embabel.agent.api.common.ranking.Ranking
import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.agent.core.Goal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionRankerTest {

    private val goals = setOf(
        Goal(name = "horoscope", description = "Get a horoscope"),
        Goal(name = "weather", description = "Get the weather"),
    )

    private val fastRetry = RankingProperties(
        maxAttempts = 2,
        backoffMillis = 1L,
        backoffMultiplier = 2.0,
        backoffMaxInterval = 10L,
    )

    private class StubDecisionProvider(
        override val isAvailable: Boolean = true,
        private val answer: ChoiceAnswer? = ChoiceAnswer(
            choice = "weather",
            probabilities = mapOf("horoscope" to 0.2, "weather" to 0.8),
            confidence = 0.6,
        ),
    ) : DecisionProvider {
        var choices: Int = 0
        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers = throw UnsupportedOperationException("use choice()")
        override fun choice(
            state: Any,
            instructions: String,
            criteria: Map<String, String?>,
        ): ChoiceAnswer {
            choices++
            return answer ?: throw IllegalStateException("Jev overloaded")
        }
    }

    private fun delegateReturningFirst(): Ranker {
        val delegate = mockk<Ranker>()
        every { delegate.rank(any(), any(), any<Collection<Goal>>()) } answers {
            Rankings(listOf(Ranking(match = goals.first(), score = 0.9)))
        }
        return delegate
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `maps choice probabilities to rankings`() {
            val delegate = mockk<Ranker>(relaxed = true)
            val ranker = DecisionRanker(StubDecisionProvider(), delegate, fastRetry)
            val rankings = ranker.rank("goal", "What is my horoscope for today?", goals)
            assertEquals("weather", rankings.rankings()[0].match.name)
            assertEquals(0.8, rankings.rankings()[0].score)
            assertEquals("horoscope", rankings.rankings()[1].match.name)
            verify(exactly = 0) { delegate.rank(any(), any(), any<Collection<Goal>>()) }
        }

        @Test
        fun `empty rankables return empty rankings`() {
            val provider = StubDecisionProvider()
            val delegate = mockk<Ranker>(relaxed = true)
            val rankings = DecisionRanker(provider, delegate, fastRetry)
                .rank("goal", "whatever", emptySet<Goal>())
            assertTrue(rankings.rankings().isEmpty())
            assertEquals(0, provider.choices)
        }
    }

    @Nested
    inner class FailOpen {

        @Test
        fun `unknown choice label falls back to the delegate`() {
            val delegate = delegateReturningFirst()
            val ranker = DecisionRanker(
                StubDecisionProvider(
                    answer = ChoiceAnswer("bogus", mapOf("bogus" to 1.0), 0.9)
                ),
                delegate,
                fastRetry,
            )
            val rankings = ranker.rank("goal", "whatever", goals)
            assertEquals("horoscope", rankings.rankings()[0].match.name)
            verify(exactly = 1) { delegate.rank(any(), any(), any<Collection<Goal>>()) }
        }

        @Test
        fun `provider failure falls back to the delegate`() {
            val delegate = delegateReturningFirst()
            val ranker = DecisionRanker(StubDecisionProvider(answer = null), delegate, fastRetry)
            val rankings = ranker.rank("goal", "whatever", goals)
            assertEquals("horoscope", rankings.rankings()[0].match.name)
            verify(exactly = 1) { delegate.rank(any(), any(), any<Collection<Goal>>()) }
        }

        @Test
        fun `unavailable provider delegates without calling`() {
            val provider = StubDecisionProvider()
            val delegate = delegateReturningFirst()
            val ranker = DecisionRanker(
                object : DecisionProvider by provider {
                    override val isAvailable: Boolean = false
                },
                delegate,
                fastRetry,
            )
            val rankings = ranker.rank("goal", "whatever", goals)
            assertEquals("horoscope", rankings.rankings()[0].match.name)
            assertEquals(0, provider.choices)
        }
    }
}
