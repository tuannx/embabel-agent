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
package com.embabel.agent.test.eval

import com.embabel.agent.api.common.decision.DecisionAnswers
import com.embabel.agent.api.common.decision.DecisionAnswer
import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DecisionQuestion

/**
 * Deterministic DecisionProvider for tests. Answers by question id and records
 * every evaluation, so tests can assert both the outcome and what was asked.
 */
class ScriptedDecisionProvider(
    private val answers: Map<String, DecisionAnswer>,
    override val isAvailable: Boolean = true,
) : DecisionProvider {

    val evaluations: MutableList<Evaluation> = mutableListOf()

    override fun evaluate(
        state: Any,
        questions: Map<String, DecisionQuestion>,
    ): DecisionAnswers {
        evaluations += Evaluation(state, questions)
        return DecisionAnswers(
            questions.mapValues { (id, _) ->
                requireNotNull(answers[id]) { "No scripted answer for question '$id'" }
            }
        )
    }

    data class Evaluation(
        val state: Any,
        val questions: Map<String, DecisionQuestion>,
    )
}
