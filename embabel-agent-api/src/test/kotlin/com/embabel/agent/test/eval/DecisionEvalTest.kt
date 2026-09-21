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

import com.embabel.agent.api.common.decision.ChoiceAnswer
import com.embabel.agent.api.common.decision.NoulAnswer
import com.embabel.agent.api.common.decision.ScoreAnswer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionEvalTest {

    private fun scripted() = ScriptedDecisionProvider(
        mapOf("question" to NoulAnswer(0.85))
    )

    private fun scriptedScorer() = ScriptedDecisionProvider(
        mapOf(
            "question" to ScoreAnswer(
                score = 1.6,
                legend = mapOf("0" to "Sloppy", "1" to "Adequate", "2" to "Polished"),
                probabilities = mapOf("0" to 0.05, "1" to 0.3, "2" to 0.65),
                confidence = 0.78,
            ),
        )
    )

    @Nested
    inner class Meets {

        @Test
        fun `above threshold passes`() {
            assertTrue(DecisionEval(scripted()).meets("output text", "Mentions risks?"))
        }

        @Test
        fun `below custom threshold fails`() {
            assertFalse(DecisionEval(scripted()).meets("output text", "Mentions risks?", threshold = 0.9))
        }
    }

    @Nested
    inner class Score {

        @Test
        fun `returns rubric placement`() {
            val answer = DecisionEval(scriptedScorer()).score(
                output = "output text",
                instructions = "How polished is the summary?",
                levels = listOf("Sloppy", "Adequate", "Polished"),
            )
            assertEquals(1.6, answer.score)
            assertEquals("Polished", answer.legend["2"])
        }
    }

    @Nested
    inner class Scripting {

        @Test
        fun `evaluations are recorded`() {
            val provider = scripted()
            DecisionEval(provider).meets("output text", "Mentions risks?")
            assertEquals(1, provider.evaluations.size)
            assertEquals("output text", provider.evaluations.single().state)
        }

        @Test
        fun `unscripted question id fails`() {
            val provider = ScriptedDecisionProvider(
                mapOf("other" to ChoiceAnswer("a", mapOf("a" to 1.0), 1.0))
            )
            assertThrows<IllegalArgumentException> {
                DecisionEval(provider).score("output", "Which?", listOf("A", "B"))
            }
        }
    }
}
