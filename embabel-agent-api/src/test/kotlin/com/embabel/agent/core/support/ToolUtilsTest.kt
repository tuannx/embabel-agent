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
package com.embabel.agent.core.support

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.common.util.StringTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolUtilsTest {

    @Nested
    inner class RenamedToolTest {

        @Test
        fun `RenamedTool changes tool name`() {
            val original = createMockTool("original-name") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals("new-name", renamed.definition.name)
        }

        @Test
        fun `RenamedTool preserves description`() {
            val original = createMockTool("tool", "Original description") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals("Original description", renamed.definition.description)
        }

        @Test
        fun `RenamedTool preserves input schema`() {
            val original = createMockTool("tool") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals(original.definition.inputSchema, renamed.definition.inputSchema)
        }

        @Test
        fun `RenamedTool preserves metadata`() {
            val original = createMockTool("tool") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals(original.metadata, renamed.metadata)
        }

        @Test
        fun `RenamedTool delegates call to original`() {
            var callInput: String? = null
            val original = createMockTool("tool") { input ->
                callInput = input
                Tool.Result.text("delegated result")
            }
            val renamed = RenamedTool(original, "new-name")

            val result = renamed.call("""{"key": "value"}""")

            assertEquals("""{"key": "value"}""", callInput)
            assertEquals("delegated result", (result as Tool.Result.Text).content)
        }
    }

    @Nested
    inner class SafelyGetToolsTest {

        @Test
        fun `safelyGetTools returns empty list for empty input`() {
            val tools = safelyGetTools(emptyList())

            assertTrue(tools.isEmpty())
        }

        @Test
        fun `safelyGetTools extracts Tool instances directly`() {
            val tool = createMockTool("direct-tool") { Tool.Result.text("{}") }
            val toolObject = ToolObject.from(tool)

            val tools = safelyGetTools(listOf(toolObject))

            assertEquals(1, tools.size)
            assertEquals("direct-tool", tools[0].definition.name)
        }

        @Test
        fun `safelyGetTools deduplicates by tool name`() {
            val tool1 = createMockTool("duplicate-name") { Tool.Result.text("1") }
            val tool2 = createMockTool("duplicate-name") { Tool.Result.text("2") }
            val toolObject = ToolObject(listOf(tool1, tool2))

            val tools = safelyGetTools(listOf(toolObject))

            assertEquals(1, tools.size)
            assertEquals("duplicate-name", tools[0].definition.name)
        }

        @Test
        fun `safelyGetTools sorts tools by name`() {
            val toolC = createMockTool("charlie") { Tool.Result.text("{}") }
            val toolA = createMockTool("alpha") { Tool.Result.text("{}") }
            val toolB = createMockTool("bravo") { Tool.Result.text("{}") }
            val toolObject = ToolObject(listOf(toolC, toolA, toolB))

            val tools = safelyGetTools(listOf(toolObject))

            assertEquals(3, tools.size)
            assertEquals("alpha", tools[0].definition.name)
            assertEquals("bravo", tools[1].definition.name)
            assertEquals("charlie", tools[2].definition.name)
        }
    }

    @Nested
    inner class SafelyGetToolsFromTest {

        @Test
        fun `safelyGetToolsFrom extracts Tool instance`() {
            val tool = createMockTool("my-tool") { Tool.Result.text("{}") }
            val toolObject = ToolObject.from(tool)

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("my-tool", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom applies filter`() {
            val tool1 = createMockTool("include-me") { Tool.Result.text("{}") }
            val tool2 = createMockTool("exclude-me") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool1, tool2),
                filter = { name -> name.startsWith("include") },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("include-me", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom applies naming strategy`() {
            val tool = createMockTool("original-name") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool),
                namingStrategy = StringTransformer { "renamed-$it" },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("renamed-original-name", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom does not rename if name unchanged`() {
            val tool = createMockTool("same-name") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool),
                namingStrategy = StringTransformer { it },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("same-name", tools[0].definition.name)
            assertFalse(tools[0] is RenamedTool)
        }

        @Test
        fun `safelyGetToolsFrom wraps renamed tools in RenamedTool`() {
            val tool = createMockTool("original") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool),
                namingStrategy = StringTransformer { "prefix-$it" },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertTrue(tools[0] is RenamedTool)
            assertEquals("prefix-original", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom deduplicates tools`() {
            val tool1 = createMockTool("same") { Tool.Result.text("1") }
            val tool2 = createMockTool("same") { Tool.Result.text("2") }
            val toolObject = ToolObject(listOf(tool1, tool2))

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
        }

        @Test
        fun `safelyGetToolsFrom sorts tools by name`() {
            val toolZ = createMockTool("zulu") { Tool.Result.text("{}") }
            val toolA = createMockTool("alpha") { Tool.Result.text("{}") }
            val toolObject = ToolObject(listOf(toolZ, toolA))

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals("alpha", tools[0].definition.name)
            assertEquals("zulu", tools[1].definition.name)
        }
    }

    enum class Collision { ACROSS_OBJECTS, AFTER_RENAMING }

    @Nested
    @ExtendWith(OutputCaptureExtension::class)
    inner class CollisionDiagnostics {

        @ParameterizedTest
        @EnumSource(Collision::class)
        @Disabled("#1834")
        fun `extraction reports both implementations and retains the first route`(collision: Collision, output: CapturedOutput) {
            val renamed = collision == Collision.AFTER_RENAMING
            val tool1 = createMockTool(if (renamed) "tool1" else "duplicate-name", "First tool") { Tool.Result.text("1") }
            val tool2 = createMockTool(if (renamed) "tool2" else "duplicate-name", "Second tool") { Tool.Result.text("2") }
            val tools = when (collision) {
                Collision.ACROSS_OBJECTS -> safelyGetTools(listOf(ToolObject(tool1), ToolObject(tool2)))
                Collision.AFTER_RENAMING -> safelyGetToolsFrom(ToolObject(listOf(tool1, tool2), namingStrategy = { "duplicate-name" }))
            }
            assertEquals(listOf("duplicate-name"), tools.map { it.definition.name })
            if (!renamed) assertEquals(listOf(tool1), tools)
            assertEquals("1", assertInstanceOf(Tool.Result.Text::class.java, tools.single().call("{}")).content)
            assertEquals(if (renamed) "tool1" else "duplicate-name", tool1.definition.name)
            assertEquals(if (renamed) "tool2" else "duplicate-name", tool2.definition.name)
            val fragments = listOf("duplicate-name", tool1.definition.name, tool2.definition.name, "First tool", "Second tool")
            val errors = output.all.lines().filter { "ERROR" in it && fragments.all(it::contains) }
            assertEquals(1, errors.size, "Expected one collision report in:\n${output.all}")
        }
    }

    private fun createMockTool(
        name: String,
        description: String = "Mock tool $name",
        onCall: (String) -> Tool.Result,
    ): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = description,
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = onCall(input)
    }
}
