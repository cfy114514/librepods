package me.kavishdevar.librepods.presentation.widgets

import org.junit.Assert.*
import org.junit.Test

class BatteryWidgetSizingTest {
    @Test fun narrowOrShortWidgetsUseCompactLabelsInsteadOfOverlappingFixedCircles() {
        assertTrue(batteryWidgetSizing(180, 132, true).compact)
        assertTrue(batteryWidgetSizing(360, 40, true).compact)
        assertFalse(batteryWidgetSizing(400, 160, true).compact)
    }

    @Test fun visibleCirclesFitEverySupportedSlotAndVerticalSpace() {
        for (phone in listOf(true, false)) for (width in 180..800 step 10) for (height in 40..300 step 10) {
            val size = batteryWidgetSizing(width, height, phone)
            if (!size.compact) {
                assertTrue(size.circleDp + 4 <= width.toFloat() / if (phone) 4 else 3)
                assertTrue(size.circleDp + 42 <= height)
                assertTrue(size.circleDp in 72f..90f)
            }
        }
    }

    @Test fun missingHostDimensionsUseAValidBoundedFallback() {
        val size = batteryWidgetSizing(0, 0, true)
        assertTrue(size.circleDp.isFinite())
        assertTrue(size.circleDp in 24f..90f)
        assertTrue(size.textSp > 0)
    }
}
