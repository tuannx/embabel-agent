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

import com.embabel.agent.api.common.decision.ChoiceQuestion
import com.embabel.agent.api.common.decision.DecisionQuestion
import com.embabel.agent.api.common.decision.NoulQuestion
import com.embabel.agent.api.common.decision.ScoreQuestion
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.common.util.EmbabelObjectMapperHolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

/**
 * Reports the Jev HTTP profile against localhost: batching, round-trip glue,
 * and request bytes as a proxy for input tokens. No live TypeSafe calls.
 */
class TypeSafeBenchmarkTest {

    private val objectMapper: ObjectMapper = EmbabelObjectMapperHolder.createDefault().get()

    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `report batched question profile`() {
        val questions: Map<String, DecisionQuestion> = buildMap {
            repeat(4) { index ->
                put("noul-$index", NoulQuestion("Noul question $index?"))
                put("choice-$index", ChoiceQuestion("Choice question $index", mapOf("a" to "Option A", "b" to "Option B")))
                put("score-$index", ScoreQuestion("Score question $index", listOf("Low", "High")))
            }
        }
        val answersJson = questions.entries.joinToString(",") { (id, question) ->
            when (question) {
                is NoulQuestion -> """"$id":{"type":"noul","noul":0.9}"""
                is ChoiceQuestion -> """"$id":{"type":"choice","choice":"a","probabilities":{"a":0.8,"b":0.2},"confidence":0.6}"""
                is ScoreQuestion -> """"$id":{"type":"score","score":0.7,"legend":{"0":"Low","1":"High"},"probabilities":{"0":0.3,"1":0.7},"confidence":0.6}"""
            }
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"model":"jev-latest","answers":{$answersJson},"usage":{"input_tokens":900,"output_tokens":120}}""")
        )
        val provider = TypeSafeDecisionProvider(
            properties = TypeSafeProperties(
                apiKey = "test-key",
                baseUrl = server.url("/").toString().removeSuffix("/"),
            ),
            objectMapper = objectMapper,
            agentPlatform = IntegrationTestUtils.dummyAgentPlatform(),
        )
        var answered = 0
        val roundTripMs = measureTimeMillis {
            answered = provider.evaluate("benchmark state text", questions).answers.size
        }
        assertEquals(questions.size, answered)
        assertEquals(1, server.requestCount)
        val requestBytes = server.takeRequest().body.readUtf8().toByteArray().size
        println(
            "[benchmark] jev call: ${questions.size} mixed questions in 1 HTTP request, " +
                "${roundTripMs}ms localhost round-trip, request=${requestBytes} bytes " +
                "(~${requestBytes / 4} input tokens); " +
                "production adds ~70-500ms Jev latency regardless of question count"
        )
    }
}
