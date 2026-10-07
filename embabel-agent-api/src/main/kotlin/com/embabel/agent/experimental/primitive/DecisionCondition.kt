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

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.Condition
import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.common.condition.ConditionDetermination
import org.slf4j.LoggerFactory

/**
 * Evaluate a condition with a bounded noul judgment instead of an LLM prompt.
 * Renders state from the blackboard or domain objects, asks one yes/no question,
 * and compares P(yes) against the threshold.
 *
 * Cheaper than PromptCondition: a single fast judgment with no text generation.
 * Without a configured decision backend this evaluates to UNKNOWN, leaving
 * planning behavior unchanged. Backend failures propagate like PromptCondition.
 *
 * @param name name of the condition
 * @param state state to judge, typically rendered from blackboard domain objects
 * @param instructions the yes/no question to evaluate, one specific judgment
 * @param threshold P(yes) at or above which the condition is true.
 * Defaults to the platform agent and goal confidence cut-offs.
 */
data class DecisionCondition(
    override val name: String,
    val state: (context: OperationContext) -> Any,
    val instructions: String,
    val threshold: ZeroToOne = 0.6,
) : Condition {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override val cost: ZeroToOne = 0.5

    override fun evaluate(context: OperationContext): ConditionDetermination {
        val provider = context.decisions()
        if (!provider.isAvailable) {
            logger.debug("Condition {}: no decision backend, UNKNOWN", name)
            return ConditionDetermination.UNKNOWN
        }
        val probability = provider.noul(state(context), instructions).noul
        logger.debug("Condition {}: P(yes)={} against threshold {}", name, probability, threshold)
        return ConditionDetermination(probability >= threshold)
    }
}
