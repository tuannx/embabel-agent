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

import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.agent.core.Goal
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.experimental.primitive.DecisionCondition
import com.embabel.agent.spi.support.LlmRanker
import com.embabel.agent.spi.support.DecisionRanker
import com.embabel.agent.spi.support.RankedChoiceResponse
import com.embabel.agent.spi.support.RankingProperties
import com.embabel.agent.spi.support.RankingsResponse
import com.embabel.agent.test.unit.FakeOperationContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

/**
 * Reports what Embabel owns in the Jev integration: glue overhead, model-call
 * counts, and request payload sizes as a proxy for input tokens.
 *
 * Transports are stubbed, so timings cover integration code only, not networks.
 * Live latency (Jev ~70-500ms flat per call including batched questions, versus
 * seconds for an LLM ranking call) is modeled in the docs from published figures.
 */
class DecisionBenchmarkTest {

    private val goals = (1..20).map { index ->
        Goal(
            name = "goal-$index",
            description = "Description of goal $index for benchmarking purposes",
        )
    }.toSet()

    private class UniformStubProvider : DecisionProvider {
        override val isAvailable: Boolean = true
        var choices: Int = 0
        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            choices++
            return DecisionAnswers(
                questions.mapValues { (id, question) ->
                    when (question) {
                        is NoulQuestion -> NoulAnswer(0.9)
                        is ChoiceQuestion -> ChoiceAnswer(
                            choice = question.criteria.keys.first(),
                            probabilities = question.criteria.keys.associateWith { 1.0 / question.criteria.size },
                            confidence = 0.5,
                        )
                        is ScoreQuestion -> ScoreAnswer(
                            score = 1.0,
                            legend = question.criteria.mapIndexed { index, level -> "$index" to level }.toMap(),
                            probabilities = emptyMap(),
                            confidence = 0.5,
                        )
                    }
                }
            )
        }
    }

    @Test
    fun `report ranking overhead and payload`() {
        val provider = UniformStubProvider()
        val unusedDelegate = mockk<com.embabel.agent.api.common.ranking.Ranker>(relaxed = true)
        val decisionRanker = DecisionRanker(provider, unusedDelegate, RankingProperties())
        var rankings: Rankings<Goal>? = null
        val jevGlueMs = measureTimeMillis {
            rankings = decisionRanker.rank("goal", "benchmark intent text", goals)
        }
        assertEquals("goal-1", rankings!!.rankings()[0].match.name)
        assertEquals(1, provider.choices)

        val llmOperations = mockk<LlmOperations>()
        var capturedPrompt = ""
        val promptSlot = io.mockk.slot<String>()
        every {
            llmOperations.doTransform<RankingsResponse>(
                prompt = capture(promptSlot),
                interaction = any(),
                outputClass = RankingsResponse::class.java,
                llmRequestEvent = null,
            )
        } answers {
            capturedPrompt = promptSlot.captured
            RankingsResponse(goals.map { RankedChoiceResponse(it.name, 0.5) })
        }
        var llmRankings: Rankings<Goal>? = null
        val llmGlueMs = measureTimeMillis {
            llmRankings = LlmRanker(llmOperations, RankingProperties())
                .rank("goal", "benchmark intent text", goals)
        }
        assertEquals(20, llmRankings!!.rankings().size)

        println(
            """
            [benchmark] ranking 20 goals (stubbed transports, integration code only):
            [benchmark]   decision ranker: ${jevGlueMs}ms glue, ${provider.choices} judgment call
            [benchmark]   llm ranker:      ${llmGlueMs}ms glue, 1 llm call, prompt=${capturedPrompt.toByteArray().size} bytes (~${capturedPrompt.toByteArray().size / 4} input tokens)
            """.trimIndent()
        )
    }

    @Test
    fun `report condition overhead`() {
        val provider = UniformStubProvider()
        val context = FakeOperationContext(decisionProvider = provider)
        val condition = DecisionCondition(
            name = "benchmark",
            state = { "benchmark state text" },
            instructions = "Is this a benchmark?",
        )
        val iterations = 100
        val totalMs = measureTimeMillis {
            repeat(iterations) {
                assertEquals(
                    com.embabel.plan.common.condition.ConditionDetermination.TRUE,
                    condition.evaluate(context)
                )
            }
        }
        println(
            "[benchmark] condition: $iterations evaluations in ${totalMs}ms " +
                "(avg ${totalMs.toDouble() / iterations}ms of glue per evaluation; " +
                "production adds one short Jev call versus one full LLM call)"
        )
    }
}
