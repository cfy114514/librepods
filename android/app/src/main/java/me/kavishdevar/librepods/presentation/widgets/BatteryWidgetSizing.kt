package me.kavishdevar.librepods.presentation.widgets

internal data class BatteryWidgetSizing(val circleDp: Float, val textSp: Float, val compact: Boolean)

internal fun batteryWidgetSizing(widthDp: Int, heightDp: Int, showPhone: Boolean): BatteryWidgetSizing {
    val width = widthDp.takeIf { it > 0 } ?: 300
    val height = heightDp.takeIf { it > 0 } ?: 132
    val cellWidth = width.toFloat() / if (showPhone) 4 else 3
    val circle = minOf(90f, cellWidth - 4f, height - 42f).coerceAtLeast(24f)
    val compact = height < 96 || circle < 72f
    val text = if (compact) 12f else 22f
    return BatteryWidgetSizing(circle, text, compact)
}
