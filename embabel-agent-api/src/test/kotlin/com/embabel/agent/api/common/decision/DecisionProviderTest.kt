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

import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DecisionProviderTest {

    private class RecordingDecisionProvider(
        override val isAvailable: Boolean = true,
        private val answers: DecisionAnswers = DecisionAnswers(
            mapOf(
                "question" to NoulAnswer(0.9),
            )
        ),
    ) : DecisionProvider {

        var lastState: Any? = null
        var lastQuestions: Map<String, DecisionQuestion>? = null

        override fun evaluate(
            state: Any,
            questions: Map<String, DecisionQuestion>,
        ): DecisionAnswers {
            lastState = state
            lastQuestions = questions
            return answers
        }
    }

    @Nested
    inner class Disabled {

        @Test
        fun `reports unavailable`() {
            assertFalse(DisabledDecisionProvider.isAvailable)
        }

        @Test
        fun `evaluate fails with actionable message`() {
            val failure = assertThrows<IllegalStateException> {
                DisabledDecisionProvider.evaluate("state", emptyMap())
            }
            assertTrue(failure.message!!.contains("TYPESAFE_API_KEY"))
        }

        @Test
        fun `convenience methods fail the same way`() {
            assertThrows<IllegalStateException> {
                DisabledDecisionProvider.noul("state", "Is this urgent?")
            }
        }
    }

    @Nested
    inner class ConvenienceMethods {

        @Test
        fun `noul delegates to evaluate with a noul question`() {
            val provider = RecordingDecisionProvider()
            val answer = provider.noul("ticket text", "Is this urgent?", yes = "Time-sensitive")
            assertEquals(0.9, answer.noul)
            assertEquals("ticket text", provider.lastState)
            val question = provider.lastQuestions!!.values.single() as NoulQuestion
            assertEquals("Is this urgent?", question.instructions)
            assertEquals("Time-sensitive", question.yes)
        }

        @Test
        fun `choice delegates to evaluate with a choice question`() {
            val provider = RecordingDecisionProvider(
                answers = DecisionAnswers(
                    mapOf(
                        "question" to ChoiceAnswer(
                            choice = "billing",
                            probabilities = mapOf("billing" to 0.84, "technical" to 0.16),
                            confidence = 0.6,
                        )
                    )
                )
            )
            val answer = provider.choice(
                state = "ticket text",
                instructions = "Which team handles this?",
                criteria = mapOf("billing" to "Payments", "technical" to "Bugs"),
            )
            assertEquals("billing", answer.choice)
            assertEquals(0.84, answer.probabilities["billing"])
        }

        @Test
        fun `score delegates to evaluate with a score question`() {
            val provider = RecordingDecisionProvider(
                answers = DecisionAnswers(
                    mapOf(
                        "question" to ScoreAnswer(
                            score = 1.6,
                            legend = mapOf("0" to "Calm", "1" to "Frustrated", "2" to "Angry"),
                            probabilities = mapOf("0" to 0.05, "1" to 0.3, "2" to 0.65),
                            confidence = 0.78,
                        )
                    )
                )
            )
            val answer = provider.score(
                state = "ticket text",
                instructions = "How frustrated is the customer?",
                criteria = listOf("Calm", "Frustrated", "Angry"),
            )
            assertEquals(1.6, answer.score)
            assertEquals("Angry", answer.legend["2"])
        }
    }

    @Nested
    inner class Answers {

        private val answers = DecisionAnswers(
            mapOf(
                "urgent" to NoulAnswer(0.92),
                "team" to ChoiceAnswer("billing", mapOf("billing" to 1.0), 0.9),
            )
        )

        @Test
        fun `typed accessors return matching answers`() {
            assertEquals(0.92, answers.noul("urgent").noul)
            assertEquals("billing", answers.choice("team").choice)
            assertEquals("billing", answers.answer<ChoiceAnswer>("team").choice)
        }

        @Test
        fun `unknown id fails`() {
            assertThrows<IllegalArgumentException> { answers.noul("missing") }
        }

        @Test
        fun `wrong type fails`() {
            assertThrows<IllegalArgumentException> { answers.score("urgent") }
        }
    }

    @Nested
    inner class QuestionValidation {

        @Test
        fun `choice needs at least one option`() {
            assertThrows<IllegalArgumentException> {
                ChoiceQuestion("Pick one", emptyMap())
            }
        }

        @Test
        fun `score needs at least two levels`() {
            assertThrows<IllegalArgumentException> {
                ScoreQuestion("Rate it", listOf("Only"))
            }
        }
    }

    @Nested
    inner class PlatformWiring {

        @Test
        fun `no decision provider without the optional backend`() {
            assertNull(IntegrationTestUtils.dummyPlatformServices().decisionProvider())
        }

        @Test
        fun `directly configured provider is returned`() {
            val provider = RecordingDecisionProvider()
            val services = IntegrationTestUtils.dummyPlatformServices(decisionProvider = provider)
            assertSame(provider, services.decisionProvider())
        }
    }
}
