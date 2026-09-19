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

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Platform configuration for bounded decisions via TypeSafe Jev.
 *
 * Platform-owned defaults (model, endpoint, short timeouts) live here and in
 * agent-platform.properties. The secret stays application-owned:
 * embabel.agent.platform.decision.typesafe.api-key=${TYPESAFE_API_KEY:}
 * in agent-application.properties. Without a key the stack is inert.
 */
@ConfigurationProperties(TypeSafeProperties.PREFIX)
data class TypeSafeProperties(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val connectTimeout: Duration = Duration.ofSeconds(1),
    val readTimeout: Duration = Duration.ofSeconds(3),
) {
    val configured: Boolean get() = enabled && apiKey.isNotBlank()

    companion object {
        const val PREFIX: String = "embabel.agent.platform.decision.typesafe"
        const val DEFAULT_MODEL: String = "jev-latest"
        const val DEFAULT_BASE_URL: String = "https://api.typesafe.ai"
    }
}
