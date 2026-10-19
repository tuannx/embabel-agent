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
package com.embabel.agent.config.models.typesafe

import com.embabel.agent.api.common.decision.ChoiceAnswer
import com.embabel.agent.api.common.decision.ChoiceQuestion
import com.embabel.agent.api.common.decision.DecisionAnswer
import com.embabel.agent.api.common.decision.DecisionAnswers
import com.embabel.agent.api.common.decision.DecisionQuestion
import com.embabel.agent.api.common.decision.NoulAnswer
import com.embabel.agent.api.common.decision.NoulQuestion
import com.embabel.agent.api.common.decision.ScoreAnswer
import com.embabel.agent.api.common.decision.ScoreQuestion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

internal const val SYSTEM_ONE_PATH: String = "/v1/systemone"

/**
 * Maps domain questions to the TypeSafe System One wire format and back.
 * Shaped by https://docs.typesafe.ai/api: POST {baseUrl}/v1/systemone with
 * {model, state, questions}, answers keyed by question id with typed payloads.
 */
internal fun requestBody(
    objectMapper: ObjectMapper,
    model: String,
    state: Any,
    questions: Map<String, DecisionQuestion>,
): String {
    val root = objectMapper.createObjectNode()
    root.put("model", model)
    root.set("state", stateNode(objectMapper, state))
    val questionsNode = objectMapper.createObjectNode()
    questions.forEach { (id, question) -> questionsNode.set(id, questionNode(objectMapper, question)) }
    root.set("questions", questionsNode)
    return objectMapper.writeValueAsString(root)
}

private fun stateNode(
    objectMapper: ObjectMapper,
    state: Any,
): JsonNode = objectMapper.valueToTree(state)

private fun questionNode(
    objectMapper: ObjectMapper,
    question: DecisionQuestion,
): ObjectNode {
    val node = objectMapper.createObjectNode()
    when (question) {
        is NoulQuestion -> {
            node.put("type", "noul")
            node.put("instructions", question.instructions)
            if (question.yes != null || question.no != null) {
                val criteria = objectMapper.createObjectNode()
                question.yes?.let { criteria.put("true", it) }
                question.no?.let { criteria.put("false", it) }
                node.set("criteria", criteria)
            }
        }
        is ChoiceQuestion -> {
            node.put("type", "choice")
            node.put("instructions", question.instructions)
            val criteria = objectMapper.createObjectNode()
            question.criteria.forEach { (option, description) ->
                if (description == null) criteria.putNull(option)
                else criteria.put(option, description)
            }
            node.set("criteria", criteria)
        }
        is ScoreQuestion -> {
            node.put("type", "score")
            node.put("instructions", question.instructions)
            val criteria: ArrayNode = objectMapper.createArrayNode()
            question.criteria.forEach { criteria.add(it) }
            node.set("criteria", criteria)
        }
    }
    return node
}

internal fun responseAnswers(
    objectMapper: ObjectMapper,
    body: String,
    questions: Map<String, DecisionQuestion>,
): DecisionAnswers {
    val root = objectMapper.readTree(body)
    val answersNode = requireNode(root, "answers")
    return DecisionAnswers(
        questions.mapValues { (id, question) ->
            parseAnswer(requireNode(answersNode, id, "answer for question '$id'"), question, id)
        }
    )
}

internal data class TokenUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
)

internal fun responseUsage(
    objectMapper: ObjectMapper,
    body: String,
): TokenUsage {
    val usage = objectMapper.readTree(body).path("usage")
    if (usage.isMissingNode || !usage.isObject) return TokenUsage(null, null)
    return TokenUsage(
        inputTokens = usage.optionalInt("input_tokens"),
        outputTokens = usage.optionalInt("output_tokens"),
    )
}

private fun parseAnswer(
    node: JsonNode,
    question: DecisionQuestion,
    id: String,
): DecisionAnswer {
    val type = node.path("type").asString(null)
    return when (question) {
        is NoulQuestion -> {
            requireType(type, "noul", id)
            NoulAnswer(noul = node.requireDouble("noul", id))
        }
        is ChoiceQuestion -> {
            requireType(type, "choice", id)
            ChoiceAnswer(
                choice = node.requireText("choice", id),
                probabilities = node.requireDoubleMap("probabilities", id),
                confidence = node.requireDouble("confidence", id),
            )
        }
        is ScoreQuestion -> {
            requireType(type, "score", id)
            ScoreAnswer(
                score = node.requireDouble("score", id),
                legend = node.requireStringMap("legend", id),
                probabilities = node.optionalDoubleMap("probabilities"),
                confidence = node.requireDouble("confidence", id),
            )
        }
    }
}

private fun requireType(
    actual: String?,
    expected: String,
    id: String,
) {
    require(actual == expected) {
        "Answer for question '$id' has type '$actual', expected '$expected'"
    }
}

private fun requireNode(
    parent: JsonNode,
    field: String,
    what: String = "field '$field'",
): JsonNode {
    val node = parent.get(field)
    require(node != null && !node.isMissingNode && !node.isNull) { "TypeSafe response is missing $what" }
    return node
}

private fun JsonNode.requireDouble(
    field: String,
    id: String,
): Double {
    val node = requireNode(this, field, "'$field' for question '$id'")
    require(node.isNumber) { "'$field' for question '$id' is not a number" }
    return node.asDouble()
}

private fun JsonNode.requireText(
    field: String,
    id: String,
): String {
    val node = requireNode(this, field, "'$field' for question '$id'")
    require(node.isString) { "'$field' for question '$id' is not a string" }
    return node.asString()
}

private fun JsonNode.requireDoubleMap(
    field: String,
    id: String,
): Map<String, Double> {
    val node = requireNode(this, field, "'$field' for question '$id'")
    require(node.isObject) { "'$field' for question '$id' is not an object" }
    return node.properties().associate { (key, value) ->
        require(value.isNumber) { "'$field' for question '$id' has non-numeric probability for '$key'" }
        key to value.asDouble()
    }
}

private fun JsonNode.requireStringMap(
    field: String,
    id: String,
): Map<String, String> {
    val node = requireNode(this, field, "'$field' for question '$id'")
    require(node.isObject) { "'$field' for question '$id' is not an object" }
    return node.properties().associate { (key, value) -> key to value.asString() }
}

private fun JsonNode.optionalDoubleMap(field: String): Map<String, Double> {
    val node = get(field) ?: return emptyMap()
    if (node.isMissingNode || node.isNull || !node.isObject) return emptyMap()
    return node.properties().associate { (key, value) -> key to value.asDouble() }
}

private fun JsonNode.optionalInt(field: String): Int? {
    val node = get(field) ?: return null
    if (node.isMissingNode || node.isNull || !node.isNumber) return null
    return node.asInt()
}
