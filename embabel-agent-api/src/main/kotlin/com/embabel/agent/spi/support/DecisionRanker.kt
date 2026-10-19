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
import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.ranking.Ranker
import com.embabel.agent.api.common.ranking.Ranking
import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import org.slf4j.LoggerFactory

/**
 * Rank with bounded choice judgments, falling back to the LLM ranker.
 *
 * Each candidate becomes a choice option, so ranking stays within a fixed set the
 * caller controls. Transient decision failures retry through the ranking retry
 * policy; persistent failures and unknown labels fail open to the delegate,
 * preserving existing autonomy cut-off behavior downstream.
 */
internal class DecisionRanker(
    private val decisionProvider: DecisionProvider,
    private val delegate: Ranker,
    private val rankingProperties: RankingProperties,
) : Ranker {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun <T> rank(
        description: String,
        userInput: String,
        rankables: Collection<T>,
    ): Rankings<T> where T : Named, T : Described {
        if (rankables.isEmpty()) {
            return Rankings(emptyList())
        }
        if (!decisionProvider.isAvailable) {
            return delegate.rank(description, userInput, rankables)
        }
        return try {
            val answer = rankingProperties.retryTemplate("decision-ranker").execute<ChoiceAnswer, Exception> {
                rankChoice(description, userInput, rankables)
            }
            toRankings(answer, rankables)
        } catch (e: Exception) {
            logger.warn(
                "Decision ranking of {} {} failed ({}), falling back to LLM ranker",
                rankables.size,
                description,
                e.javaClass.simpleName,
            )
            delegate.rank(description, userInput, rankables)
        }
    }

    private fun <T> rankChoice(
        description: String,
        userInput: String,
        rankables: Collection<T>,
    ): ChoiceAnswer where T : Named, T : Described =
        decisionProvider.choice(
            state = mapOf("input" to userInput, "kind" to description),
            instructions = "Which $description best matches the input",
            criteria = rankables.associate { it.name to it.description },
        )

    private fun <T> toRankings(
        answer: ChoiceAnswer,
        rankables: Collection<T>,
    ): Rankings<T> where T : Named, T : Described {
        val byName = rankables.associateBy { it.name }
        require(byName.containsKey(answer.choice)) {
            "Decision ranker returned choice '${answer.choice}' not in the available choices"
        }
        return Rankings(
            rankings = rankables.map {
                Ranking(
                    match = it,
                    score = answer.probabilities[it.name]?.coerceIn(0.0, 1.0) ?: 0.0,
                )
            }.sortedByDescending { it.score }
        )
    }
}
