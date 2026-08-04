package com.dtyan.spendtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.ui.theme.ChartPalette
import com.dtyan.spendtracker.ui.theme.SpendTrackerTheme
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Модели данных графиков
// ---------------------------------------------------------------------------

data class ChartSlice(val label: String, val value: Long, val color: Color)
data class ChartBar(val label: String, val value: Long, val color: Color)
data class ChartLinePoint(val label: String, val value: Long)

/** Текст-заглушка при отсутствии данных. */
private const val NO_DATA = "Нет данных"

/** Зазор между секторами кольца, градусы. */
private const val SLICE_GAP_DEG = 1.5f

/** Число горизонтальных линий сетки (включая нулевую). */
private const val GRID_LINES = 4

// ---------------------------------------------------------------------------
// Donut
// ---------------------------------------------------------------------------

/**
 * Кольцевая диаграмма с подписью в центре.
 * [selectedIndex] и индекс в [onSliceClick] — позиции в исходном списке [slices].
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    centerTitle: String = "",
    centerValue: String = "",
    selectedIndex: Int? = null,
    onSliceClick: ((Int) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    // Отрицательные и нулевые значения не рисуем, но помним исходные индексы.
    val visible = remember(slices) {
        slices.mapIndexedNotNull { index, slice -> if (slice.value > 0) index to slice else null }
    }
    val total = remember(visible) { visible.sumOf { it.second.value } }

    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val titleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface

    val clickModifier = if (onSliceClick != null && visible.isNotEmpty() && total > 0) {
        Modifier.pointerInput(visible, onSliceClick) {
            detectTapGestures { tap ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val bump = 6.dp.toPx()
                val radius = min(w, h) / 2f - bump
                if (radius <= 0f) return@detectTapGestures
                val stroke = radius * 0.26f
                val dx = tap.x - cx
                val dy = tap.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                // Попадание только в само кольцо: не в «дырку» и не за внешний край.
                if (dist < radius - stroke || dist > radius + bump) return@detectTapGestures
                // Угол от 12 часов по часовой стрелке.
                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                deg = ((deg % 360f) + 360f) % 360f
                var acc = 0f
                for ((originalIndex, slice) in visible) {
                    val sweep = slice.value.toFloat() / total.toFloat() * 360f
                    if (deg >= acc && deg < acc + sweep) {
                        onSliceClick(originalIndex)
                        return@detectTapGestures
                    }
                    acc += sweep
                }
                // Хвост из-за накопления погрешности — отдаём последний сектор.
                onSliceClick(visible.last().first)
            }
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 140.dp, minHeight = 180.dp)
            .then(clickModifier)
    ) {
        val bump = 6.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f - bump
        if (radius <= 0f) return@Canvas
        val stroke = radius * 0.26f
        val arcRadius = radius - stroke / 2f
        val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
        val innerWidth = ((radius - stroke) * 1.7f).toInt().coerceAtLeast(1)

        if (visible.isEmpty() || total <= 0L) {
            // Заглушка: серое кольцо и надпись.
            drawCircle(
                color = emptyColor,
                radius = arcRadius,
                center = center,
                style = Stroke(width = stroke),
            )
            drawCenteredText(
                measurer = measurer,
                text = NO_DATA,
                style = TextStyle(color = titleColor, fontSize = 13.sp),
                center = center,
                dy = 0f,
                maxWidthPx = innerWidth,
            )
            return@Canvas
        }

        var start = -90f
        for ((originalIndex, slice) in visible) {
            val sweep = slice.value.toFloat() / total.toFloat() * 360f
            val drawSweep = if (sweep > SLICE_GAP_DEG * 2f) sweep - SLICE_GAP_DEG else sweep
            val selected = selectedIndex == originalIndex
            if (selected) {
                // Выделенный сектор — толще и слегка выступает наружу.
                val selRadius = arcRadius + bump * 0.45f
                drawArc(
                    color = slice.color,
                    startAngle = start,
                    sweepAngle = drawSweep,
                    useCenter = false,
                    topLeft = Offset(center.x - selRadius, center.y - selRadius),
                    size = Size(selRadius * 2f, selRadius * 2f),
                    style = Stroke(width = stroke * 1.3f),
                )
            } else {
                drawArc(
                    color = slice.color,
                    startAngle = start,
                    sweepAngle = drawSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
            }
            start += sweep
        }

        // Центральные подписи.
        val titleStyle = TextStyle(color = titleColor, fontSize = 12.sp)
        val valueStyle = TextStyle(color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        val hasTitle = centerTitle.isNotEmpty()
        val hasValue = centerValue.isNotEmpty()
        if (hasTitle && hasValue) {
            drawCenteredText(measurer, centerTitle, titleStyle, center, -12.dp.toPx(), innerWidth)
            drawCenteredText(measurer, centerValue, valueStyle, center, 8.dp.toPx(), innerWidth)
        } else if (hasTitle) {
            drawCenteredText(measurer, centerTitle, titleStyle, center, 0f, innerWidth)
        } else if (hasValue) {
            drawCenteredText(measurer, centerValue, valueStyle, center, 0f, innerWidth)
        }
    }
}

// ---------------------------------------------------------------------------
// BarChart
// ---------------------------------------------------------------------------

/** Вертикальные столбцы — помесячная динамика. */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    valueFormatter: (Long) -> String = { MoneyFormat.formatCompact(it) },
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Canvas(modifier = modifier.defaultMinSize(minHeight = 180.dp)) {
        val labelStyle = TextStyle(color = axisColor, fontSize = 10.sp)
        if (bars.isEmpty()) {
            drawCenteredText(
                measurer = measurer,
                text = NO_DATA,
                style = TextStyle(color = axisColor, fontSize = 13.sp),
                center = Offset(size.width / 2f, size.height / 2f),
                dy = 0f,
                maxWidthPx = size.width.toInt().coerceAtLeast(1),
            )
            return@Canvas
        }

        val maxValue = bars.maxOf { it.value }.coerceAtLeast(0L)
        val top = niceMax(maxValue)
        val steps = GRID_LINES - 1

        // Подписи оси Y и ширина левого поля.
        val yLayouts = (0..steps).map { i ->
            measurer.measure(text = valueFormatter(top * i / steps), style = labelStyle)
        }
        val gutter = (yLayouts.maxOf { it.size.width }).toFloat() + 8.dp.toPx()
        val xLayouts = bars.map { measurer.measure(text = it.label, style = labelStyle) }
        val bottomGutter = (xLayouts.maxOf { it.size.height }).toFloat() + 6.dp.toPx()

        val plotLeft = gutter
        val plotRight = size.width - 2.dp.toPx()
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - bottomGutter
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        // Сетка с подписями.
        for (i in 0..steps) {
            val y = plotBottom - plotHeight * i / steps
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = yLayouts[i]
            drawText(
                layout,
                topLeft = Offset(
                    plotLeft - 6.dp.toPx() - layout.size.width,
                    y - layout.size.height / 2f,
                ),
            )
        }

        // Столбцы.
        val slot = plotWidth / bars.size
        val barWidth = min(slot * 0.6f, 44.dp.toPx()).coerceAtLeast(1f)
        val corner = min(barWidth / 2f, 6.dp.toPx())
        bars.forEachIndexed { index, bar ->
            val value = bar.value.coerceAtLeast(0L)
            val h = plotHeight * (value.toFloat() / top.toFloat())
            val left = plotLeft + slot * index + (slot - barWidth) / 2f
            if (h <= 0.5f) return@forEachIndexed
            val rect = Rect(left, plotBottom - h, left + barWidth, plotBottom)
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = rect,
                        topLeft = CornerRadius(corner, corner),
                        topRight = CornerRadius(corner, corner),
                        bottomRight = CornerRadius.Zero,
                        bottomLeft = CornerRadius.Zero,
                    )
                )
            }
            drawPath(path = path, color = bar.color)
        }

        // Подписи X — прореживаем, если столбцов много.
        val step = when {
            bars.size <= 8 -> 1
            bars.size <= 16 -> 2
            else -> (bars.size + 7) / 8
        }
        bars.indices.forEach { index ->
            if (index % step != 0) return@forEach
            val layout = xLayouts[index]
            val cx = plotLeft + slot * index + slot / 2f
            val x = (cx - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(x, plotBottom + 4.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------
// LineChart
// ---------------------------------------------------------------------------

/** Ломаная с необязательной заливкой — дневная динамика. */
@Composable
fun LineChart(
    points: List<ChartLinePoint>,
    modifier: Modifier = Modifier,
    showArea: Boolean = true,
    valueFormatter: (Long) -> String = { MoneyFormat.formatCompact(it) },
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val lineColor = MaterialTheme.colorScheme.primary
    val markerInner = MaterialTheme.colorScheme.surface

    Canvas(modifier = modifier.defaultMinSize(minHeight = 180.dp)) {
        val labelStyle = TextStyle(color = axisColor, fontSize = 10.sp)
        if (points.isEmpty()) {
            drawCenteredText(
                measurer = measurer,
                text = NO_DATA,
                style = TextStyle(color = axisColor, fontSize = 13.sp),
                center = Offset(size.width / 2f, size.height / 2f),
                dy = 0f,
                maxWidthPx = size.width.toInt().coerceAtLeast(1),
            )
            return@Canvas
        }

        // Базовая линия всегда 0 — заодно снимает проблему max == min.
        val maxValue = points.maxOf { it.value }.coerceAtLeast(0L)
        val top = niceMax(maxValue)
        val steps = GRID_LINES - 1

        val yLayouts = (0..steps).map { i ->
            measurer.measure(text = valueFormatter(top * i / steps), style = labelStyle)
        }
        val gutter = (yLayouts.maxOf { it.size.width }).toFloat() + 8.dp.toPx()
        val xIndices = linkedSetOf(0, points.size / 2, points.size - 1).toList()
        val xLayouts = xIndices.associateWith { measurer.measure(text = points[it].label, style = labelStyle) }
        val bottomGutter = (xLayouts.values.maxOf { it.size.height }).toFloat() + 6.dp.toPx()

        val plotLeft = gutter
        val plotRight = size.width - 4.dp.toPx()
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - bottomGutter
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        for (i in 0..steps) {
            val y = plotBottom - plotHeight * i / steps
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = yLayouts[i]
            drawText(
                layout,
                topLeft = Offset(plotLeft - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f),
            )
        }

        // Координаты точек. Единственная точка — по центру, без деления на ноль.
        val denom = (points.size - 1).coerceAtLeast(1)
        val offsets = points.mapIndexed { index, point ->
            val x = if (points.size == 1) plotLeft + plotWidth / 2f else plotLeft + plotWidth * index / denom
            val value = point.value.coerceAtLeast(0L)
            val y = plotBottom - plotHeight * (value.toFloat() / top.toFloat())
            Offset(x, y)
        }

        if (showArea && offsets.size > 1) {
            val area = Path().apply {
                moveTo(offsets.first().x, plotBottom)
                offsets.forEach { lineTo(it.x, it.y) }
                lineTo(offsets.last().x, plotBottom)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0f)),
                    startY = plotTop,
                    endY = plotBottom,
                ),
            )
        }

        if (offsets.size > 1) {
            val line = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                for (i in 1 until offsets.size) lineTo(offsets[i].x, offsets[i].y)
            }
            drawPath(path = line, color = lineColor, style = Stroke(width = 2.dp.toPx()))
        } else {
            // Одна точка — горизонтальная линия на её уровне.
            val y = offsets.first().y
            drawLine(
                color = lineColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // Маркеры — только когда точек немного.
        if (offsets.size <= 40) {
            offsets.forEach { p ->
                drawCircle(color = lineColor, radius = 3.5f.dp.toPx(), center = p)
                drawCircle(color = markerInner, radius = 1.8f.dp.toPx(), center = p)
            }
        }

        // Подписи X: первая, средняя, последняя.
        xIndices.forEach { index ->
            val layout = xLayouts[index] ?: return@forEach
            val cx = offsets[index].x
            val x = (cx - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(x, plotBottom + 4.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------
// HorizontalBarList
// ---------------------------------------------------------------------------

/** Рейтинг категорий: название, сумма и полоса-индикатор доли. */
@Composable
fun HorizontalBarList(
    items: List<ChartBar>,
    modifier: Modifier = Modifier,
    valueFormatter: (Long) -> String = { MoneyFormat.format(it) },
    onItemClick: ((Int) -> Unit)? = null,
) {
    if (items.isEmpty()) {
        Text(
            text = NO_DATA,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 12.dp),
        )
        return
    }
    val maxValue = items.maxOf { it.value.coerceAtLeast(0L) }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier) {
        items.forEachIndexed { index, item ->
            val rowModifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .let { base ->
                    if (onItemClick != null) base.clickable { onItemClick(index) } else base
                }
                .padding(horizontal = 4.dp, vertical = 7.dp)

            Column(modifier = rowModifier) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = valueFormatter(item.value),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                // Доля от максимума; деление на ноль и отрицательные значения отсечены.
                val fraction = if (maxValue <= 0L) 0f
                else (item.value.coerceAtLeast(0L).toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(trackColor),
                ) {
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                                .clip(CircleShape)
                                .background(item.color),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ChartLegend
// ---------------------------------------------------------------------------

/** Легенда: кружок + название + сумма, переносится по строкам. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    valueFormatter: (Long) -> String = { MoneyFormat.format(it) },
) {
    if (slices.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(slice.color),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = valueFormatter(slice.value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Вспомогательное
// ---------------------------------------------------------------------------

/** Округление максимума вверх до «красивого» 1/2/5 × 10^n. */
private fun niceMax(value: Long): Long {
    if (value <= 0L) return 1L
    val exp = floor(log10(value.toDouble()))
    val pow = 10.0.pow(exp)
    val normalized = value.toDouble() / pow
    val multiplier = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    val result = multiplier * pow
    return if (result >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else result.toLong().coerceAtLeast(1L)
}

/** Текст по центру точки [center] со смещением [dy] по вертикали. */
private fun DrawScope.drawCenteredText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    center: Offset,
    dy: Float,
    maxWidthPx: Int,
) {
    if (text.isEmpty()) return
    val layout = measurer.measure(
        text = text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1)),
    )
    drawText(
        layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y + dy - layout.size.height / 2f,
        ),
    )
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

private val demoSlices = listOf(
    ChartSlice("Продукты", 1_845_000L, ChartPalette[0]),
    ChartSlice("Транспорт", 920_000L, ChartPalette[1]),
    ChartSlice("Кафе", 640_000L, ChartPalette[2]),
    ChartSlice("Здоровье", 415_000L, ChartPalette[3]),
    ChartSlice("Развлечения", 260_000L, ChartPalette[4]),
)

private val demoBars = listOf(
    ChartBar("янв", 3_120_000L, ChartPalette[0]),
    ChartBar("фев", 2_740_000L, ChartPalette[0]),
    ChartBar("мар", 4_010_000L, ChartPalette[0]),
    ChartBar("апр", 3_560_000L, ChartPalette[0]),
    ChartBar("май", 2_980_000L, ChartPalette[0]),
    ChartBar("июн", 4_620_000L, ChartPalette[0]),
)

private val demoPoints = (1..30).map { day ->
    ChartLinePoint("$day", (40_000L + (day * 7919L) % 190_000L))
}

@Preview(showBackground = true, widthDp = 340, heightDp = 260, name = "Donut")
@Composable
private fun DonutChartPreview() {
    SpendTrackerTheme {
        Surface {
            DonutChart(
                slices = demoSlices,
                modifier = Modifier.fillMaxWidth().height(240.dp),
                centerTitle = "Всего за месяц",
                centerValue = MoneyFormat.format(demoSlices.sumOf { it.value }),
                selectedIndex = 1,
                onSliceClick = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 260, name = "Donut пусто")
@Composable
private fun DonutChartEmptyPreview() {
    SpendTrackerTheme {
        Surface {
            DonutChart(
                slices = emptyList(),
                modifier = Modifier.fillMaxWidth().height(240.dp),
                centerTitle = "Всего",
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 220, name = "Bar")
@Composable
private fun BarChartPreview() {
    SpendTrackerTheme {
        Surface {
            BarChart(
                bars = demoBars,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 220, name = "Line")
@Composable
private fun LineChartPreview() {
    SpendTrackerTheme {
        Surface {
            LineChart(
                points = demoPoints,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 240, name = "Рейтинг категорий")
@Composable
private fun HorizontalBarListPreview() {
    SpendTrackerTheme {
        Surface {
            HorizontalBarList(
                items = demoSlices.map { ChartBar(it.label, it.value, it.color) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                onItemClick = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 340, heightDp = 140, name = "Легенда")
@Composable
private fun ChartLegendPreview() {
    SpendTrackerTheme {
        Surface {
            ChartLegend(
                slices = demoSlices,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
    }
}
