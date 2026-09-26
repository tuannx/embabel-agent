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

import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.ScoreAnswer

/**
 * Scores agent outputs against rubrics for evals and quality-gate tests.
 * Thin convenience over DecisionProvider: test code asserts on the returned values.
 */
class DecisionEval(
    private val decisionProvider: DecisionProvider,
) {

    val isAvailable: Boolean get() = decisionProvider.isAvailable

    /**
     * Whether P(yes) for the given judgment over the output meets the threshold.
     */
    @JvmOverloads
    fun meets(
        output: Any,
        instructions: String,
        threshold: Double = 0.6,
    ): Boolean = decisionProvider.noul(output, instructions).noul >= threshold

    /**
     * Where the output falls on the ordered rubric, with distribution and confidence.
     */
    @JvmOverloads
    fun score(
        output: Any,
        instructions: String,
        levels: List<String>,
    ): ScoreAnswer = decisionProvider.score(output, instructions, levels)
}
