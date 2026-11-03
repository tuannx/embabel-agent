/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.spi.support

import com.vmlens.api.AllInterleavings
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Positive control for the vmlens pass: the canonical unsynchronized counter
 * increment, which any working vmlens setup must flag as a data race in the
 * report generated from the same run.
 *
 * A "Success" verdict for any other scenario in this source set is only
 * trustworthy if this control is flagged: vmlens silently reports success
 * when a scenario is too large for its loop threshold and the racing
 * operations never reach the trace (a false negative measured in the
 * standalone pilot, 2026-11-02). If the control is not flagged in the report,
 * every other result of the run counts as unverified (verdict warn), whatever
 * the report says.
 */
class VmlensPositiveControlTest {

    private class Counter {
        var value = 0

        fun inc() {
            value++
        }
    }

    @Test
    fun plainCounterIncrementAcrossThreadsExploresInterleavings() {
        var iterations = 0
        val all = AllInterleavings("vmlens-positive-control")
        try {
            while (all.hasNext()) {
                iterations++
                val counter = Counter()
                val first = Thread { counter.inc() }
                val second = Thread { counter.inc() }
                first.start()
                second.start()
                first.join()
                second.join()
            }
        } finally {
            all.close()
        }
        println("POSITIVE-CONTROL iterations=$iterations")
        assertTrue(
            iterations > 1,
            "vmlens must explore more than one interleaving for the control to be meaningful, explored $iterations",
        )
    }
}
