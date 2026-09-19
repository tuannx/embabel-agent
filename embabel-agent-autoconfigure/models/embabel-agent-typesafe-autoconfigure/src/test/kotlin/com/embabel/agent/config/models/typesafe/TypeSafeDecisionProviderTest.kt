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
import com.embabel.agent.api.event.AgentPlatformEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.DecisionUsageEvent
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.common.util.EmbabelObjectMapperHolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeSafeDecisionProviderTest {

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

    private fun provider(
        apiKey: String = "test-key",
        listener: AgenticEventListener? = null,
    ): TypeSafeDecisionProvider =
        TypeSafeDecisionProvider(
            properties = TypeSafeProperties(
                apiKey = apiKey,
                baseUrl = server.url("/").toString().removeSuffix("/"),
            ),
            objectMapper = objectMapper,
            eventListener = listener,
            agentPlatform = IntegrationTestUtils.dummyAgentPlatform(),
        )

    private fun questions(): Map<String, DecisionQuestion> = mapOf(
        "department" to ChoiceQuestion(
            instructions = "Which team should handle this",
            criteria = mapOf(
                "billing" to "Payment or subscription issues",
                "technical" to "Bugs or integration problems",
                "sales" to null,
            ),
        ),
        "frustration" to ScoreQuestion(
            instructions = "How frustrated the customer appears",
            criteria = listOf("Calm, just stating facts", "Frustrated but civil", "Very angry"),
        ),
        "is_urgent" to NoulQuestion(
            instructions = "The message conveys urgency or time-sensitivity",
        ),
    )

    private val successBody = """
        {
          "model": "jev-latest",
          "answers": {
            "department": {
              "type": "choice",
              "choice": "billing",
              "probabilities": {"billing": 0.84, "technical": 0.159, "sales": 0.001},
              "confidence": 0.596
            },
            "frustration": {
              "type": "score",
              "score": 1.035,
              "legend": {"0": "Calm, just stating facts", "1": "Frustrated but civil", "2": "Very angry"},
              "probabilities": {"0": 0.1, "1": 0.765, "2": 0.135},
              "confidence": 0.842
            },
            "is_urgent": {"type": "noul", "noul": 0.999}
          },
          "usage": {"input_tokens": 312, "output_tokens": 48}
        }
    """.trimIndent()

    private fun enqueueSuccess() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(successBody)
        )
    }

    @Nested
    inner class Mapping {

        @Test
        fun `maps noul choice and score answers`() {
            enqueueSuccess()
            val answers = provider().evaluate("Stripe payouts failing for 3 days", questions())
            assertEquals("billing", answers.choice("department").choice)
            assertEquals(0.84, answers.choice("department").probabilities["billing"])
            assertEquals(0.596, answers.choice("department").confidence)
            assertEquals(1.035, answers.score("frustration").score)
            assertEquals("Very angry", answers.score("frustration").legend["2"])
            assertEquals(0.999, answers.noul("is_urgent").noul)
        }

        @Test
        fun `sends model state and questions to systemone`() {
            enqueueSuccess()
            provider().evaluate("Stripe payouts failing for 3 days", questions())
            val request = server.takeRequest()
            assertEquals("/v1/systemone", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = objectMapper.readTree(request.body.readUtf8())
            assertEquals("jev-latest", body.get("model").asString())
            assertEquals("Stripe payouts failing for 3 days", body.get("state").asString())
            assertEquals("choice", body.get("questions").get("department").get("type").asString())
            assertEquals("score", body.get("questions").get("frustration").get("type").asString())
            assertEquals("noul", body.get("questions").get("is_urgent").get("type").asString())
            assertEquals(
                "Payment or subscription issues",
                body.get("questions").get("department").get("criteria").get("billing").asString()
            )
            assertTrue(body.get("questions").get("department").get("criteria").get("sales").isNull)
            assertEquals(
                3,
                body.get("questions").get("frustration").get("criteria").size()
            )
        }

        @Test
        fun `serializes structured state as an object`() {
            enqueueSuccess()
            provider().evaluate(Ticket("payouts failing", 3), mapOf("is_urgent" to NoulQuestion("Urgent?")))
            val request = server.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            assertTrue(body.get("state").isObject)
            assertEquals("payouts failing", body.get("state").get("subject").asString())
        }

        @Test
        fun `missing answer fails`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"model":"jev-latest","answers":{}}""")
            )
            val failure = assertThrows<IllegalArgumentException> {
                provider().evaluate("state", mapOf("is_urgent" to NoulQuestion("Urgent?")))
            }
            assertTrue(failure.message!!.contains("is_urgent"))
        }

        @Test
        fun `answer type mismatch fails`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"model":"jev-latest","answers":{"is_urgent":{"type":"choice"}}}""")
            )
            assertThrows<IllegalArgumentException> {
                provider().evaluate("state", mapOf("is_urgent" to NoulQuestion("Urgent?")))
            }
        }
    }

    @Nested
    inner class UsageEvents {

        @Test
        fun `publishes usage without state or key`() {
            enqueueSuccess()
            val listener = RecordingPlatformListener()
            val state = "Stripe payouts failing for 3 days, customer id 42"
            provider(listener = listener).evaluate(state, questions())
            val event = listener.platformEvents.filterIsInstance<DecisionUsageEvent>().single()
            assertEquals("jev-latest", event.model)
            assertEquals(312, event.inputTokens)
            assertEquals(48, event.outputTokens)
            assertEquals(listOf("department", "frustration", "is_urgent"), event.questionIds)
            assertFalse(event.toString().contains("Stripe"))
            assertFalse(event.toString().contains("test-key"))
        }
    }

    @Nested
    inner class Failures {

        @Test
        fun `unauthorized surfaces status without leaking the key`() {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid key"}"""))
            val failure = assertThrows<IllegalStateException> {
                provider().evaluate("state", mapOf("is_urgent" to NoulQuestion("Urgent?")))
            }
            assertTrue(failure.message!!.contains("401"))
            assertFalse(failure.message!!.contains("test-key"))
        }

        @Test
        fun `unavailable without a key`() {
            val unkeyed = provider(apiKey = "")
            assertFalse(unkeyed.isAvailable)
            val failure = assertThrows<IllegalStateException> {
                unkeyed.evaluate("state", mapOf("is_urgent" to NoulQuestion("Urgent?")))
            }
            assertTrue(failure.message!!.contains("TYPESAFE_API_KEY"))
            assertEquals(0, server.requestCount)
        }
    }
}

private data class Ticket(val subject: String, val daysOpen: Int)

private class RecordingPlatformListener : AgenticEventListener {
    val platformEvents = mutableListOf<AgentPlatformEvent>()
    override fun onPlatformEvent(event: AgentPlatformEvent) {
        platformEvents += event
    }
}
