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
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentWithValidAchievesGoalMethod as ExistingAgentWithValidAchievesGoalMethod
import com.embabel.agent.api.dsl.EvilWizardAgent
import com.embabel.agent.api.dsl.MagicVictim
import com.embabel.agent.api.dsl.evenMoreEvilWizardWithStructuredInput
import org.springframework.context.annotation.Profile
import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Context
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.ContextRepository
import com.embabel.agent.spi.config.spring.AgentPlatformProperties.ProcessType
import com.embabel.agent.spi.support.InMemoryContext
import com.embabel.agent.spi.support.InMemoryContextRepository
import com.embabel.agent.support.Dog
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultAgentPlatformTest {

    private fun raw(
        l: AgenticEventListener = EventSavingAgenticEventListener(),
        contextRepository: ContextRepository = InMemoryContextRepository(),
    ): AgentPlatform {
        return DefaultAgentPlatform(
            "name",
            "description",
            processType = ProcessType.SIMPLE,
            mockk(),
            mockk(relaxed = true),
            l,
            contextRepository = contextRepository,
            asyncer = mockk(),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder(mockk()),
            outputChannel = DevNullOutputChannel,
            templateRenderer = mockk(),
        )
    }

    @Test
    fun `starts with empty blackboard`() {
        val dap = raw()
        val ap = dap.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
        assertEquals(0, ap.objects.size)
    }

    @Test
    fun `binds parameters to blackboard`() {
        val dap = raw()
        val ap = dap.createAgentProcess(
            evenMoreEvilWizard(), ProcessOptions(), mapOf(
                "dog" to Dog("Duke")
            )
        )
        assertEquals(1, ap.objects.size)
        assertEquals("Duke", ((ap["dog"] as Dog).name))
    }

    @Nested
    inner class ContextLoading {

        @Test
        fun `loads context`() {
            val contextRepository = InMemoryContextRepository()
            var context: Context = InMemoryContext(id = "1234")
            context.bind("otherDog", Dog("Apollo"))
            context = contextRepository.save(context)
            val dap = raw(contextRepository = contextRepository)
            val ap = dap.createAgentProcess(
                evenMoreEvilWizard(),
                ProcessOptions(contextId = ContextId(context.id)),
                mapOf(
                    "dog" to Dog("Duke")
                ),
            )
            assertEquals(2, ap.objects.size, "Should have 2 objects, not ${ap.objects.size}: ${ap.objects}")
            assertEquals("Duke", ((ap["dog"] as Dog).name))
            assertEquals("Apollo", ((ap["otherDog"] as Dog).name))
        }

    }

    @Nested
    inner class PlatformServicesOverride {

        @Test
        fun `subclass can override platformServices with a different implementation`() {
            val base = raw()
            val custom = CustomPlatformServices(base.platformServices)
            val subclassed: AgentPlatform = SubclassedAgentPlatform(custom)

            assertNotNull(subclassed.platformServices)
            assertInstanceOf(CustomPlatformServices::class.java, subclassed.platformServices)
        }
    }

    enum class Capability(val sharedName: String) { GOAL("done"), ACTION("thing"), CONDITION("testCondition") }

    @Nested
    @ExtendWith(OutputCaptureExtension::class)
    inner class DuplicateNames {

        @Test
        @Disabled("#1834")
        fun `annotated agents sharing a simple class name report both packages`(output: CapturedOutput) {
            val reader = AgentMetadataReader()
            val agent = assertInstanceOf(CoreAgent::class.java, reader.createAgentMetadata(ExistingAgentWithValidAchievesGoalMethod()))
            val duplicate = assertInstanceOf(CoreAgent::class.java, reader.createAgentMetadata(AgentWithValidAchievesGoalMethod()))
            assertEquals("AgentWithValidAchievesGoalMethod", agent.name)
            assertEquals(agent.name, duplicate.name)
            val platform = raw()
            platform.deploy(agent)
            platform.deploy(duplicate)
            assertEquals(listOf(duplicate), platform.agents())
            assertEquals(duplicate.goals, platform.goals)
            assertReported(output, agent.name, ExistingAgentWithValidAchievesGoalMethod::class.java.name, AgentWithValidAchievesGoalMethod::class.java.name)
        }

        @ParameterizedTest
        @EnumSource(Capability::class)
        @Disabled("#1834")
        fun `same named capabilities across agents report both owners`(capability: Capability, output: CapturedOutput) {
            val sharedName = capability.sharedName
            fun source(owner: String) = agent(owner, description = owner) {
                val condition by conditionOf(name = if (capability == Capability.CONDITION) sharedName else "$owner.testCondition") { true }
                transformation<UserInput, MagicVictim>(name = if (capability == Capability.ACTION) sharedName else "$owner.thing") { MagicVictim("Hamish") }
                goal(
                    name = if (capability == Capability.GOAL) sharedName else "$owner.done",
                    description = owner,
                    satisfiedBy = MagicVictim::class,
                )
            }
            val (first, second, third) = listOf(EvilWizardAgent, evenMoreEvilWizard(), evenMoreEvilWizardWithStructuredInput())
                .map { source(it.name) }.sortedBy { it.name }
            val platform = raw()
            platform.deploy(third)
            platform.deploy(second)
            if (capability == Capability.GOAL) assertEquals(second.goals, platform.goals)
            platform.deploy(first)
            assertEquals(listOf(first, second, third), platform.agents())
            when (capability) {
                Capability.GOAL -> assertEquals(first.goals, platform.goals)
                Capability.ACTION -> assertEquals(first.actions, platform.actions)
                Capability.CONDITION -> assertEquals(first.conditions, platform.conditions)
            }
            assertReported(output, sharedName, first.name, second.name, third.name)
        }

        private fun assertReported(output: CapturedOutput, vararg fragments: String) {
            val identifiers = fragments.map { Regex("\\b${Regex.escape(it)}\\b") }
            val errors = output.all.lines().filter { line ->
                "ERROR" in line && identifiers.all { it.containsMatchIn(line) }
            }
            assertEquals(1, errors.size, "Expected one collision report in:\n${output.all}")
        }
    }

    @Agent(description = "valid goal method")
    @Profile("issue1834-fixtures")
    class AgentWithValidAchievesGoalMethod {
        @Action
        @AchievesGoal(description = "goal")
        fun goal(input: UserInput): String = "dummy"
    }

}

private class CustomPlatformServices(delegate: PlatformServices) : PlatformServices by delegate

private class SubclassedAgentPlatform(
    customServices: PlatformServices,
) : DefaultAgentPlatform(
    name = "subclassed",
    description = "subclassed platform",
    processType = ProcessType.SIMPLE,
    llmOperations = mockk(),
    toolGroupResolver = mockk(relaxed = true),
    eventListener = EventSavingAgenticEventListener(),
    asyncer = mockk(),
    embabelObjectMapperHolder = EmbabelObjectMapperHolder(mockk()),
    outputChannel = DevNullOutputChannel,
    templateRenderer = mockk(),
) {
    override val platformServices: PlatformServices = customServices
}
