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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.core.Goal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** A2A publication must report distinct goals that produce the same skill identity, see #1834. */
class FromGoalsAgentSkillFactoryTest {

    @Nested
    @ExtendWith(OutputCaptureExtension::class)
    inner class CollisionDiagnostics {

        @Test
        @Disabled("#1834")
        fun `same named goals report the colliding skill id and both meanings`(output: CapturedOutput) {
            val goal1 = Goal(name = "done", description = "First goal", outputType = null)
            val goal2 = goal1.copy(description = "Second goal")
            val goals = linkedSetOf(goal1, goal2)
            assertEquals(2, goals.size)
            val skills = FromGoalsAgentSkillFactory(goals).skills("app")
            assertEquals(listOf("app_goal_done", "app_goal_done"), skills.map { it.id })
            assertEquals(listOf("First goal", "Second goal"), skills.map { it.description })
            assertEquals(listOf("done", "done"), goals.map { it.name })
            val errors = output.all.lines().filter {
                "ERROR" in it && listOf("app_goal_done", "First goal", "Second goal").all(it::contains)
            }
            assertEquals(1, errors.size, "Expected one skill collision report in:\n${output.all}")
        }
    }
}
