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
package com.embabel.agent.spi.loop.support

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Nested
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.Assertions.assertInstanceOf
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.spi.loop.MockTool
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionResult
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class ToolExecutionSupportTest {

    enum class Collision { EXISTING_TOOL, SAME_BATCH }

    @Nested
    @ExtendWith(OutputCaptureExtension::class)
    inner class CollisionDiagnostics {

        @ParameterizedTest
        @EnumSource(Collision::class)
        @Disabled("#1834")
        fun `injection reports collisions after decoration and retains the first tool`(collision: Collision, output: CapturedOutput) {
            val tool1 = MockTool("existing", "Existing") { Tool.Result.text("existing") }
            val tool2 = MockTool("added", "Added") { Tool.Result.text("added") }
            val decorated = MockTool("existing", tool2.definition.description) { tool2.call(it) }
            val existing = collision == Collision.EXISTING_TOOL
            val available = if (existing) mutableListOf<Tool>(tool1) else mutableListOf()
            val injected = mutableListOf<Tool>()
            val additions = if (existing) listOf(tool2) else listOf(tool1, tool2)
            applyToolInjection(
                toolCall = ToolCall("call-1", "source", "{}"),
                resultContent = "{}",
                conversationHistory = emptyList(),
                availableTools = available,
                iteration = 1,
                injectionStrategy = object : ToolInjectionStrategy {
                    override fun evaluate(context: ToolInjectionContext) = ToolInjectionResult(toolsToAdd = additions)
                },
                objectMapper = jacksonObjectMapper(),
                toolDecorator = { if (it === tool2) decorated else it },
                injectedTools = injected,
                logger = LoggerFactory.getLogger(ToolExecutionSupportTest::class.java),
            )
            assertEquals(listOf(tool1), available)
            assertEquals(if (existing) emptyList() else listOf(tool1), injected)
            assertEquals("existing", assertInstanceOf(Tool.Result.Text::class.java, available.single().call("{}")).content)
            assertEquals("added", tool2.definition.name)
            val fragments = listOf("existing", "added", "Existing", "Added")
            val errors = output.all.lines().filter { "ERROR" in it && fragments.all(it::contains) }
            assertEquals(1, errors.size, "Expected one collision report in:\n${output.all}")
        }
    }

    @Test
    fun `executeTool returns content and publishes callbacks`() {
        val callbacks = mutableListOf<String>()
        val inspector = object : ToolCallInspector {
            override fun beforeToolCall(context: BeforeToolCallContext) {
                callbacks += "before:${context.toolCall.name}"
            }

            override fun afterToolCall(context: AfterToolCallContext) {
                callbacks += "after:${context.resultAsString}"
            }
        }
        val tool = MockTool("lookup", "Lookup") { Tool.Result.text("found") }

        val executed = executeTool(
            tool = tool,
            toolCall = ToolCall("call-1", "lookup", "{}"),
            toolCallContext = ToolCallContext.EMPTY,
            toolCallInspectors = listOf(inspector),
        )

        assertTrue(executed.result is Tool.Result.Text)
        assertEquals("found", executed.content)
        assertEquals(listOf("before:lookup", "after:found"), callbacks)
    }

    @Test
    fun `applyToolInjection shares decoration deduplication removal and JSON context semantics`() {
        val existing = MockTool("existing", "Existing") { Tool.Result.text("existing") }
        val removed = MockTool("removed", "Removed") { Tool.Result.text("removed") }
        val added = MockTool("added", "Added") { Tool.Result.text("added") }
        val decorated = MockTool("added", "Decorated") { Tool.Result.text("decorated") }
        val available = mutableListOf<Tool>(existing, removed)
        val injectedTools = mutableListOf<Tool>()
        val removedTools = mutableListOf<Tool>()
        var evaluatedContext: ToolInjectionContext? = null
        val strategy = object : ToolInjectionStrategy {
            override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
                evaluatedContext = context
                return ToolInjectionResult(
                    toolsToAdd = listOf(existing, added),
                    toolsToRemove = listOf(removed),
                )
            }
        }

        applyToolInjection(
            toolCall = ToolCall("call-1", "source", "{\"input\":true}"),
            resultContent = "{\"answer\":42}",
            conversationHistory = listOf(UserMessage("question")),
            availableTools = available,
            iteration = 3,
            injectionStrategy = strategy,
            objectMapper = jacksonObjectMapper(),
            toolDecorator = { if (it.definition.name == "added") decorated else it },
            injectedTools = injectedTools,
            removedTools = removedTools,
        )

        assertEquals(listOf("existing", "added"), available.map { it.definition.name })
        assertTrue(decorated === available.last())
        assertEquals(listOf(decorated), injectedTools)
        assertEquals(listOf(removed), removedTools)
        assertEquals(3, evaluatedContext?.iterationCount)
        assertEquals("source", evaluatedContext?.lastToolCall?.toolName)
        assertTrue(evaluatedContext?.lastToolCall?.resultObject is Map<*, *>)
    }
}
