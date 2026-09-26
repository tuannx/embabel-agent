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
package com.embabel.agent.autoconfigure.models.typesafe;

import com.embabel.agent.config.models.typesafe.TypeSafeDecisionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for bounded decisions via TypeSafe Jev.
 * <p>
 * Active by default but inert without an API key: without
 * {@code embabel.agent.platform.decision.typesafe.api-key} (or {@code TYPESAFE_API_KEY})
 * a disabled DecisionProvider is registered and ranking, conditions, and action code
 * keep their existing behavior. Set
 * {@code embabel.agent.platform.decision.typesafe.enabled=false} to remove the bean entirely.
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@ConditionalOnProperty(
        prefix = "embabel.agent.platform.decision.typesafe",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import(TypeSafeDecisionConfig.class)
public class AgentTypeSafeAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AgentTypeSafeAutoConfiguration.class);

    public AgentTypeSafeAutoConfiguration() {
        logger.info("AgentTypeSafeAutoConfiguration about to proceed...");
    }
}
