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

import com.embabel.agent.api.common.decision.DecisionProvider
import com.embabel.agent.api.common.decision.DisabledDecisionProvider
import com.embabel.agent.autoconfigure.models.typesafe.AgentTypeSafeAutoConfiguration
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypeSafeAutoConfigurationTest {

    private fun runner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentTypeSafeAutoConfiguration::class.java))

    @Nested
    inner class Enabled {

        @Test
        fun `api key registers an available provider`() {
            runner()
                .withPropertyValues(
                    "embabel.agent.platform.decision.typesafe.api-key=test-key",
                )
                .run { context ->
                    val provider = context.getBean(DecisionProvider::class.java)
                    assertIs<TypeSafeDecisionProvider>(provider)
                    assertTrue(provider.isAvailable)
                }
        }

        @Test
        fun `missing key registers the disabled provider`() {
            runner()
                .run { context ->
                    val provider = context.getBean(DecisionProvider::class.java)
                    assertIs<DisabledDecisionProvider>(provider)
                    assertFalse(provider.isAvailable)
                }
        }

        @Test
        fun `model is overridable`() {
            runner()
                .withPropertyValues(
                    "embabel.agent.platform.decision.typesafe.api-key=test-key",
                    "embabel.agent.platform.decision.typesafe.model=jev-1.13",
                )
                .run { context ->
                    val properties = context.getBean(TypeSafeProperties::class.java)
                    assertTrue(properties.model == "jev-1.13")
                }
        }
    }

    @Nested
    inner class Disabled {

        @Test
        fun `enabled false registers no provider`() {
            runner()
                .withPropertyValues(
                    "embabel.agent.platform.decision.typesafe.enabled=false",
                    "embabel.agent.platform.decision.typesafe.api-key=test-key",
                )
                .run { context ->
                    assertTrue(context.getBeansOfType(DecisionProvider::class.java).isEmpty())
                }
        }
    }
}
