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

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.support.duplicates.alpha.Wizard as AlphaWizard
import com.embabel.agent.core.support.duplicates.beta.Wizard as BetaWizard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/**
 * Expected behaviour when agents deployed to one platform share names, see issue #1834.
 * Each case is silently lost today, so the tests stay disabled until it is reported.
 */
@ExtendWith(OutputCaptureExtension::class)
class AgentPlatformDuplicateNameTest {

    data class Answer(val text: String)

    private val platform = IntegrationTestUtils.dummyAgentPlatform()

    private fun dslAgent(
        agentName: String,
        goalName: String = "$agentName-goal",
        actionName: String = "$agentName-action",
        conditionName: String = "$agentName-condition",
    ) = agent(agentName, description = "$agentName description") {
        val condition by conditionOf(name = conditionName) { true }
        transformation<UserInput, Answer>(name = actionName) { Answer(agentName) }
        goal(name = goalName, description = "$agentName meaning", satisfiedBy = Answer::class)
    }

    private fun assertErrorMentions(output: CapturedOutput, vararg fragments: String) {
        val line = output.out.lines().firstOrNull { line -> "ERROR" in line && fragments.all { it in line } }
        assertNotNull(line, "Expected an ERROR line mentioning ${fragments.toList()} in:\n${output.out}")
    }

    @Nested
    inner class AgentNames {

        @Test
        @Disabled("#1834: the second deploy replaces the first agent without any report")
        fun `deploying a second agent with an existing name is reported`(output: CapturedOutput) {
            platform.deploy(dslAgent(agentName = "AgentA", goalName = "first"))
            platform.deploy(dslAgent(agentName = "AgentA", goalName = "second"))

            assertErrorMentions(output, "AgentA")
        }

        @Test
        @Disabled("#1834: annotated agents default to the simple class name, so the second replaces the first")
        fun `annotated agents sharing a simple class name are reported with both packages`(output: CapturedOutput) {
            val reader = AgentMetadataReader()
            platform.deploy(requireNotNull(reader.createAgentMetadata(AlphaWizard())))
            platform.deploy(requireNotNull(reader.createAgentMetadata(BetaWizard())))

            assertErrorMentions(output, "Wizard", "duplicates.alpha", "duplicates.beta")
        }
    }

    @Nested
    inner class NamesAcrossAgents {

        @Test
        @Disabled("#1834: AgentPlatform.goals keeps one goal per name and drops the other silently")
        fun `goals with the same name in different agents are reported`(output: CapturedOutput) {
            platform.deploy(dslAgent(agentName = "AgentA", goalName = "done"))
            platform.deploy(dslAgent(agentName = "AgentB", goalName = "done"))

            assertErrorMentions(output, "done", "AgentA", "AgentB")
        }

        @Test
        @Disabled("#1834: AgentPlatform.actions keeps one action per name, so platform-wide planning loses the other")
        fun `actions with the same name in different agents are reported`(output: CapturedOutput) {
            platform.deploy(dslAgent(agentName = "AgentA", actionName = "shared"))
            platform.deploy(dslAgent(agentName = "AgentB", actionName = "shared"))

            assertErrorMentions(output, "shared", "AgentA", "AgentB")
        }

        @Test
        @Disabled("#1834: AgentPlatform.conditions keeps one condition per name and drops the other silently")
        fun `conditions with the same name in different agents are reported`(output: CapturedOutput) {
            platform.deploy(dslAgent(agentName = "AgentA", conditionName = "ready"))
            platform.deploy(dslAgent(agentName = "AgentB", conditionName = "ready"))

            assertErrorMentions(output, "ready", "AgentA", "AgentB")
        }
    }
}
