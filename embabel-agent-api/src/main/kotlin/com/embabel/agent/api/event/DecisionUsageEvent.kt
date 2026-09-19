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
package com.embabel.agent.api.event

import com.embabel.agent.core.AgentPlatform
import java.time.Instant

/**
 * Emitted once per decision-provider call, for usage and cost tracking.
 * Carries token counts and latency only: never state, questions, or answers,
 * which may contain PII.
 */
class DecisionUsageEvent(
    override val agentPlatform: AgentPlatform,
    val provider: String,
    val model: String,
    val questionIds: List<String>,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMillis: Long,
    override val timestamp: Instant = Instant.now(),
) : AgentPlatformEvent {

    override fun toString(): String =
        "DecisionUsageEvent(provider=$provider, model=$model, questions=$questionIds, " +
            "inputTokens=$inputTokens, outputTokens=$outputTokens, latencyMillis=$latencyMillis)"
}
