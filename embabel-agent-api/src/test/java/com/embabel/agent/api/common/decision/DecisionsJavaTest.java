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
package com.embabel.agent.api.common.decision;

import com.embabel.agent.experimental.primitive.DecisionCondition;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.plan.common.condition.ConditionDetermination;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interop for bounded decisions: OperationContext.decisions(),
 * DecisionCondition, and typed answers.
 */
public class DecisionsJavaTest {

    static class FixedDecisionProvider implements DecisionProvider {

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public DecisionAnswers evaluate(Object state, Map<String, DecisionQuestion> questions) {
            return new DecisionAnswers(Map.of("question", new NoulAnswer(0.92)));
        }
    }

    @Test
    public void decisionsAnswerFromJava() {
        var context = FakeOperationContext.create()
                .withDecisionProvider(new FixedDecisionProvider());
        var answer = context.decisions().noul("The payouts keep failing", "Does this message express urgency?");
        assertEquals(0.92, answer.getNoul());
    }

    @Test
    public void conditionEvaluatesFromJava() {
        var context = FakeOperationContext.create()
                .withDecisionProvider(new FixedDecisionProvider());
        var condition = new DecisionCondition(
                "urgentTicket",
                operationContext -> "The payouts keep failing",
                "Does this message express urgency?",
                0.6);
        assertEquals(ConditionDetermination.TRUE, condition.evaluate(context));
    }

    @Test
    public void questionTypesConstructFromJava() {
        var noul = new NoulQuestion("Urgent?", "Time-sensitive", null);
        var choice = new ChoiceQuestion("Which team", Map.of("billing", "Payments"));
        var score = new ScoreQuestion("How frustrated", List.of("Calm", "Angry"));
        assertEquals("Urgent?", noul.getInstructions());
        assertEquals(1, choice.getCriteria().size());
        assertEquals(2, score.getCriteria().size());
    }
}
