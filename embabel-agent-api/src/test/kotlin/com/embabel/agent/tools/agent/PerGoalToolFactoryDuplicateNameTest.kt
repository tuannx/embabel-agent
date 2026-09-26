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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Export
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.integration.RandomRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/**
 * Expected behaviour when distinct goals map to the same published tool name, see issue #1834.
 */
@ExtendWith(OutputCaptureExtension::class)
class PerGoalToolFactoryDuplicateNameTest {

    data class Answer(val text: String)

    private fun agentWithGoal(agentName: String, goalName: String) =
        agent(agentName, description = "$agentName description") {
            transformation<UserInput, Answer>(name = "$agentName-action") { Answer(agentName) }
            goal(
                name = goalName,
                description = "$agentName meaning",
                satisfiedBy = Answer::class,
                export = Export(remote = true, startingInputTypes = setOf(UserInput::class.java)),
            )
        }

    @Test
    @Disabled("#1834: both goals publish as app_Wizard_done; the MCP server keeps the last one with only a WARN")
    fun `distinct goals that map to the same tool name are reported`(output: CapturedOutput) {
        val platform = IntegrationTestUtils.dummyAgentPlatform()
        platform.deploy(agentWithGoal(agentName = "AlphaWizard", goalName = "com.example.alpha.Wizard.done"))
        platform.deploy(agentWithGoal(agentName = "BetaWizard", goalName = "com.example.beta.Wizard.done"))
        val factory = PerGoalToolFactory(Autonomy(platform, RandomRanker(), forAutonomyTesting()), "app")

        factory.goalTools(remoteOnly = true, listeners = emptyList())

        val fragments = listOf("app_Wizard_done", "com.example.alpha.Wizard.done", "com.example.beta.Wizard.done")
        val line = output.out.lines().firstOrNull { line -> "ERROR" in line && fragments.all { it in line } }
        assertNotNull(line, "Expected an ERROR line mentioning $fragments in:\n${output.out}")
    }
}
