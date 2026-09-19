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

import com.embabel.agent.api.common.decision.DecisionAnswers
import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DecisionQuestion
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.DecisionUsageEvent
import com.embabel.agent.core.AgentPlatform
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper

/**
 * [DecisionProvider] over the TypeSafe System One API.
 *
 * Sends state with typed questions and returns calibrated answers. This is not an LLM:
 * it never generates text, calls tools, or plans, and must not be exposed as a
 * chat model. Used only for bounded judgments: ranking, conditions, and direct
 * OperationContext decisions.
 *
 * Never logs the API key or request state, which may carry PII. Only question ids,
 * model, latency, and token counts reach the logs and usage events.
 */
class TypeSafeDecisionProvider(
    private val properties: TypeSafeProperties,
    private val objectMapper: ObjectMapper,
    private val eventListener: AgenticEventListener? = null,
    private val agentPlatform: AgentPlatform? = null,
    restClient: RestClient? = null,
) : DecisionProvider {

    private val logger = LoggerFactory.getLogger(TypeSafeDecisionProvider::class.java)

    override val isAvailable: Boolean = properties.configured

    private val restClient: RestClient = restClient ?: defaultRestClient()

    private fun defaultRestClient(): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory()
        requestFactory.setConnectTimeout(properties.connectTimeout)
        requestFactory.setReadTimeout(properties.readTimeout)
        return RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(properties.baseUrl)
            .build()
    }

    override fun evaluate(
        state: Any,
        questions: Map<String, DecisionQuestion>,
    ): DecisionAnswers {
        check(isAvailable) {
            "TypeSafe decisions are not configured. Set TYPESAFE_API_KEY to enable them."
        }
        if (questions.isEmpty()) {
            return DecisionAnswers(emptyMap())
        }
        val started = System.currentTimeMillis()
        val body = requestBody(
            objectMapper = objectMapper,
            model = properties.model,
            state = state,
            questions = questions,
        )
        logger.debug(
            "TypeSafe {} request: model={}, questions={}",
            SYSTEM_ONE_PATH,
            properties.model,
            questions.keys.sorted(),
        )
        val responseBody = post(body)
        val answers = responseAnswers(objectMapper, responseBody, questions)
        val usage = responseUsage(objectMapper, responseBody)
        publishUsage(
            questionIds = questions.keys.sorted(),
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            latencyMillis = System.currentTimeMillis() - started,
        )
        return answers
    }

    private fun post(body: String): String {
        try {
            val response = restClient.post()
                .uri(SYSTEM_ONE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer ${properties.apiKey}")
                .body(body)
                .retrieve()
                .body(String::class.java)
            return requireNotNull(response) { "TypeSafe returned an empty response body" }
        } catch (e: RestClientResponseException) {
            logger.warn(
                "TypeSafe request failed: status={}",
                e.statusCode,
            )
            throw IllegalStateException(
                "TypeSafe request failed with status ${e.statusCode}: ${e.responseBodyAsString}",
                e,
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: RuntimeException) {
            logger.warn("TypeSafe request failed: error={}", e.javaClass.simpleName)
            throw IllegalStateException("TypeSafe request failed: ${e.message}", e)
        }
    }

    private fun publishUsage(
        questionIds: List<String>,
        inputTokens: Int?,
        outputTokens: Int?,
        latencyMillis: Long,
    ) {
        val platform = agentPlatform ?: return
        val listener = eventListener ?: return
        try {
            listener.onPlatformEvent(
                DecisionUsageEvent(
                    agentPlatform = platform,
                    provider = PROVIDER,
                    model = properties.model,
                    questionIds = questionIds,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    latencyMillis = latencyMillis,
                )
            )
        } catch (e: RuntimeException) {
            logger.warn("Decision usage listener failed: error={}", e.javaClass.simpleName)
        }
    }

    companion object {
        const val PROVIDER: String = "typesafe"
    }
}
