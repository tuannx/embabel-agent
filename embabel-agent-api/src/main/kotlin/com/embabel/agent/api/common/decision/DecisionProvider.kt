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

/**
 * Bounded, non-generative judgments over application state.
 *
 * A DecisionProvider answers typed questions against a state object and returns
 * calibrated values code can branch on, sort by, and route with. It never generates
 * text, calls tools, or plans. For open-ended generation use PromptRunner instead.
 *
 * Implementations are optional platform capabilities. Without one configured,
 * [com.embabel.agent.api.common.PlatformServices.decisionProvider] returns null and
 * [com.embabel.agent.api.common.OperationContext.decisions] reports unavailable,
 * leaving ranking, conditions, and action code on their existing behavior.
 */
interface DecisionProvider {

    /**
     * Whether this provider can answer questions right now.
     * False when the backing service is unconfigured, for example when no API key is set.
     */
    val isAvailable: Boolean

    /**
     * Evaluate questions against state, in parallel and in isolation.
     * @param state domain state to judge: a String for text, or any object serialized as structured data
     * @param questions questions keyed by caller-chosen ids; answers come back under the same ids
     */
    fun evaluate(
        state: Any,
        questions: Map<String, @JvmSuppressWildcards DecisionQuestion>,
    ): DecisionAnswers

    /**
     * Probability the answer to this yes/no question is yes, between 0 and 1.
     */
    fun noul(
        state: Any,
        instructions: String,
    ): NoulAnswer = noul(state, instructions, null, null)

    /**
     * Probability the answer to this yes/no question is yes, between 0 and 1.
     * @param yes what a yes near 1 means
     * @param no what a no near 0 means
     */
    fun noul(
        state: Any,
        instructions: String,
        yes: String? = null,
        no: String? = null,
    ): NoulAnswer =
        evaluate(state, mapOf("question" to NoulQuestion(instructions, yes, no))).noul("question")

    /**
     * Which of the given options best fits the state, with the full distribution and confidence.
     */
    fun choice(
        state: Any,
        instructions: String,
        criteria: Map<String, String?>,
    ): ChoiceAnswer =
        evaluate(state, mapOf("question" to ChoiceQuestion(instructions, criteria))).choice("question")

    /**
     * Where the state falls on the ordered rubric, with the level distribution and confidence.
     */
    fun score(
        state: Any,
        instructions: String,
        criteria: List<String>,
    ): ScoreAnswer =
        evaluate(state, mapOf("question" to ScoreQuestion(instructions, criteria))).score("question")
}

/**
 * One typed question to evaluate against a state.
 */
sealed interface DecisionQuestion {

    /**
     * What the model should decide, phrased as one specific, well-scoped judgment.
     */
    val instructions: String
}

/**
 * Yes/no question. Answer is the probability that the answer is yes.
 */
data class NoulQuestion @JvmOverloads constructor(
    override val instructions: String,
    val yes: String? = null,
    val no: String? = null,
) : DecisionQuestion

/**
 * Pick one option from a defined set.
 * @param criteria option to rubric description; null when an option needs no extra detail
 */
data class ChoiceQuestion(
    override val instructions: String,
    val criteria: Map<String, String?>,
) : DecisionQuestion {
    init {
        require(criteria.isNotEmpty()) { "ChoiceQuestion needs at least one option" }
    }
}

/**
 * Rate the state along an ordered rubric.
 * @param criteria ordered level descriptions, least to greatest; at least two levels
 */
data class ScoreQuestion(
    override val instructions: String,
    val criteria: List<String>,
) : DecisionQuestion {
    init {
        require(criteria.size >= 2) { "ScoreQuestion needs at least two levels" }
    }
}

/**
 * One typed answer, matching its question.
 */
sealed interface DecisionAnswer

/**
 * Probability the answer is yes, from 0 (no) to 1 (yes).
 */
data class NoulAnswer(
    val noul: Double,
) : DecisionAnswer

/**
 * @param choice highest-probability option
 * @param probabilities every option mapped to its probability; sums to 1
 * @param confidence certainty derived from the distribution, from 0 to 1
 */
data class ChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) : DecisionAnswer

/**
 * @param score probability-weighted value across levels; can land between levels
 * @param legend level index to its description
 * @param probabilities level index mapped to its probability; sums to 1
 * @param confidence certainty derived from the distribution, from 0 to 1
 */
data class ScoreAnswer(
    val score: Double,
    val legend: Map<String, String>,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) : DecisionAnswer

/**
 * Answers keyed by the question ids from the request.
 */
data class DecisionAnswers(
    val answers: Map<String, DecisionAnswer>,
) {

    fun noul(id: String): NoulAnswer = typed(id, NoulAnswer::class.java)

    fun choice(id: String): ChoiceAnswer = typed(id, ChoiceAnswer::class.java)

    fun score(id: String): ScoreAnswer = typed(id, ScoreAnswer::class.java)

    fun <T : DecisionAnswer> typed(
        id: String,
        type: Class<T>,
    ): T {
        val answer = requireNotNull(answers[id]) { "No answer for question '$id'" }
        require(type.isInstance(answer)) {
            "Answer for question '$id' is ${answer.javaClass.simpleName}, not ${type.simpleName}"
        }
        return type.cast(answer)
    }
}

/**
 * Typed accessor for Kotlin call sites.
 */
inline fun <reified T : DecisionAnswer> DecisionAnswers.answer(id: String): T =
    typed(id, T::class.java)

/**
 * Unconfigured provider. Reports unavailable and fails fast with an actionable message
 * when used, so deployments without a decision backend keep existing behavior until
 * application code explicitly opts into decisions.
 */
object DisabledDecisionProvider : DecisionProvider {

    override val isAvailable: Boolean = false

    override fun evaluate(
        state: Any,
        questions: Map<String, DecisionQuestion>,
    ): DecisionAnswers =
        throw IllegalStateException(
            "No DecisionProvider is configured. Set TYPESAFE_API_KEY to enable bounded decisions, " +
                "or guard usage with DecisionProvider.isAvailable."
        )
}
