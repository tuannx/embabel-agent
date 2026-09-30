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
package com.embabel.agent.mcpserver.support

import com.embabel.agent.core.Export
import com.embabel.agent.core.Goal
import com.embabel.agent.mcpserver.async.support.PerGoalAsyncMcpStartingInputTypesPromptPublisher
import com.embabel.agent.mcpserver.sync.support.PerGoalStartingInputTypesPromptPublisher
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** Both MCP publishers must report distinct schemas sharing a prompt name, see #1834. */
class PerGoalPromptPublisherTest {

    enum class Mode { SYNC, ASYNC }

    @Nested
    @ExtendWith(OutputCaptureExtension::class)
    inner class CollisionDiagnostics {

        @ParameterizedTest
        @EnumSource(Mode::class)
        @Disabled("#1834")
        fun `same simple input names report both qualified schemas`(mode: Mode, output: CapturedOutput) {
            val goal = Goal(
                name = "done", description = "Two different input schemas", outputType = null,
                export = Export(startingInputTypes = linkedSetOf(SimpleTestClass::class.java, PromptUtilsTest.SimpleTestClass::class.java)),
            )
            val prompts = when (mode) {
                Mode.SYNC -> PerGoalStartingInputTypesPromptPublisher(mockk()).promptsForGoal(goal).map { it.prompt }
                Mode.ASYNC -> PerGoalAsyncMcpStartingInputTypesPromptPublisher(mockk()).promptsForGoal(goal).map { it.prompt }
            }
            assertEquals(listOf("SimpleTestClass_done", "SimpleTestClass_done"), prompts.map { it.name() })
            assertEquals(listOf(listOf("name"), listOf("name", "age")), prompts.map { it.arguments.map { arg -> arg.name } })
            val fragments = listOf("SimpleTestClass_done", SimpleTestClass::class.java.name, PromptUtilsTest.SimpleTestClass::class.java.name)
            val errors = output.all.lines().filter { "ERROR" in it && fragments.all(it::contains) }
            assertEquals(1, errors.size, "Expected one prompt collision report in:\n${output.all}")
        }
    }

    private data class SimpleTestClass(val name: String)
}
