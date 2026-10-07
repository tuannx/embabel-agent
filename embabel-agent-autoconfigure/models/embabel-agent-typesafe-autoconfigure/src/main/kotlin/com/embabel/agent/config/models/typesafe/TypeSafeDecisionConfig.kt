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
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the TypeSafe [DecisionProvider]. Always registers a bean so application
 * code can inject DecisionProvider and guard on isAvailable; without an API key the
 * disabled provider keeps ranking, conditions, and decisions on existing behavior.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TypeSafeProperties::class)
class TypeSafeDecisionConfig {

    private val logger = LoggerFactory.getLogger(TypeSafeDecisionConfig::class.java)

    @Bean
    @ConditionalOnMissingBean(DecisionProvider::class)
    fun typeSafeDecisionProvider(
        properties: TypeSafeProperties,
        embabelObjectMapperHolder: ObjectProvider<EmbabelObjectMapperHolder>,
        eventListener: ObjectProvider<AgenticEventListener>,
        agentPlatform: ObjectProvider<AgentPlatform>,
    ): DecisionProvider {
        if (!properties.configured) {
            logger.info(
                "TypeSafe decisions disabled: set TYPESAFE_API_KEY to enable bounded judgments."
            )
            return DisabledDecisionProvider
        }
        logger.info(
            "TypeSafe decisions enabled: model={}, baseUrl={}",
            properties.model,
            properties.baseUrl,
        )
        return TypeSafeDecisionProvider(
            properties = properties,
            objectMapper = embabelObjectMapperHolder.getIfAvailable {
                EmbabelObjectMapperHolder.createDefault()
            }.get(),
            eventListener = eventListener.getIfAvailable(),
            agentPlatform = agentPlatform.getIfAvailable(),
        )
    }
}
