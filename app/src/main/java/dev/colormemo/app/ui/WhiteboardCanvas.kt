package dev.colormemo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardStroke

private const val HISTORY_LIMIT = 25
private const val POINT_SPACING = 2.5f
private const val ERASER_RADIUS = 28f

private val inkColors = listOf(
    0xFF202124L to "黑色",
    0xFFD93025L to "红色",
    0xFFB85A00L to "橙色",
    0xFF188038L to "绿色",
    0xFF1967D2L to "蓝色",
    0xFF8430CEL to "紫色",
)

@Composable
internal fun WhiteboardEditor(
    whiteboard: Whiteboard,
    onWhiteboardChange: (Whiteboard) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val currentBoard by rememberUpdatedState(whiteboard)
    val onChange by rememberUpdatedState(onWhiteboardChange)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var ink by rememberSaveable { mutableStateOf(inkColors.first().first) }
    var lineWidth by rememberSaveable { mutableStateOf(6f) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var eraserPosition by remember { mutableStateOf<WhiteboardPoint?>(null) }
    var undoBoards by remember { mutableStateOf(emptyList<Whiteboard>()) }
    var redoBoards by remember { mutableStateOf(emptyList<Whiteboard>()) }
    var lastPublished by remember { mutableStateOf(whiteboard) }
    var showClearDialog by remember { mutableStateOf(false) }
    var limitReached by remember { mutableStateOf(false) }
    val controlsEnabled = !readOnly && !drawing

    fun publish(next: Whiteboard) {
        lastPublished = next
        onChange(next)
    }

    fun rememberUndo(previous: Whiteboard) {
        undoBoards = (undoBoards + previous).takeLast(HISTORY_LIMIT)
        redoBoards = emptyList()
    }

    // A replaced document must not inherit undo entries from the previous one.
    LaunchedEffect(whiteboard) {
        if (whiteboard != lastPublished) {
            undoBoards = emptyList()
            redoBoards = emptyList()
            lastPublished = whiteboard
            limitReached = false
        }
    }
    LaunchedEffect(readOnly) {
        if (readOnly) showClearDialog = false
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!readOnly) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = !erasing, onClick = { erasing = false }, enabled = controlsEnabled, label = { Text("画笔") })
                FilterChip(selected = erasing, onClick = { erasing = true }, enabled = controlsEnabled, label = { Text("橡皮") })
                IconButton(
                    onClick = {
                        val previous = undoBoards.last()
                        undoBoards = undoBoards.dropLast(1)
                        redoBoards = (redoBoards + currentBoard).takeLast(HISTORY_LIMIT)
                        limitReached = false
                        publish(previous)
                    },
                    enabled = controlsEnabled && undoBoards.isNotEmpty(),
                ) { Icon(Icons.AutoMirrored.Outlined.Undo, "撤销白板操作") }
                IconButton(
                    onClick = {
                        val next = redoBoards.last()
                        redoBoards = redoBoards.dropLast(1)
                        undoBoards = (undoBoards + currentBoard).takeLast(HISTORY_LIMIT)
                        publish(next)
                    },
                    enabled = controlsEnabled && redoBoards.isNotEmpty(),
                ) { Icon(Icons.AutoMirrored.Outlined.Redo, "重做白板操作") }
                IconButton(
                    onClick = { showClearDialog = true },
                    enabled = controlsEnabled && whiteboard.strokes.isNotEmpty(),
                ) { Icon(Icons.Outlined.DeleteOutline, "清空白板") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                inkColors.forEach { (color, label) ->
                    val selected = ink == color && !erasing
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                enabled = controlsEnabled,
                                role = Role.RadioButton,
                                onClick = { ink = color; erasing = false },
                            )
                            .semantics { contentDescription = "${label}画笔" }
                            .padding(5.dp)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                            .padding(3.dp)
                            .background(Color(color), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = Color.White)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("线宽", style = MaterialTheme.typography.labelMedium)
                listOf(3f to "细", 6f to "中", 14f to "粗").forEach { (width, label) ->
                    FilterChip(
                        selected = lineWidth == width && !erasing,
                        onClick = { lineWidth = width; erasing = false },
                        enabled = controlsEnabled,
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = "${label}画笔线宽" },
                    )
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .semantics { contentDescription = if (readOnly) "白板预览" else "白板画布，可用手指或触控笔绘画" }
                .pointerInput(readOnly, ink, lineWidth, erasing) {
                    if (readOnly) return@pointerInput
                    awaitEachGesture {
                        // Consume the first down before a scrolling parent sees movement.
                        // Tracking this ID throughout prevents a second finger becoming a line.
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        down.consume()
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                        fun pointAt(position: Offset): WhiteboardPoint = whiteboardPoint(
                            position.x / size.width * Whiteboard.CANVAS_SIZE,
                            position.y / size.height * Whiteboard.CANVAS_SIZE,
                        )
                        fun isInside(position: Offset): Boolean = position.x >= 0f && position.y >= 0f &&
                            position.x <= size.width && position.y <= size.height
                        if (!isInside(down.position)) return@awaitEachGesture

                        val beforeGesture = currentBoard
                        var working = beforeGesture
                        var pointCount = working.strokes.sumOf { it.points.size }
                        var recordedUndo = false
                        var lastPoint = pointAt(down.position)

                        fun update(next: Whiteboard) {
                            if (next == working) return
                            if (!recordedUndo) {
                                rememberUndo(beforeGesture)
                                recordedUndo = true
                            }
                            working = next
                            // Publish during the gesture, so Save, rotation or cancellation
                            // never drops the visible portion of an unfinished stroke.
                            publish(next)
                        }

                        drawing = true
                        try {
                            if (erasing) {
                                eraserPosition = lastPoint
                                update(working.eraseBetween(lastPoint, lastPoint, ERASER_RADIUS))
                                limitReached = false
                            } else {
                                if (working.strokes.size >= Whiteboard.MAX_STROKES || pointCount >= Whiteboard.MAX_POINTS) {
                                    limitReached = true
                                    return@awaitEachGesture
                                }
                                update(working.copy(strokes = working.strokes + WhiteboardStroke(
                                    color = ink,
                                    width = lineWidth,
                                    points = listOf(lastPoint),
                                )))
                                pointCount++
                                limitReached = false
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                event.changes.forEach { it.consume() }
                                // Stop at the edge instead of connecting a path across a lost
                                // pointer, an outside movement or another finger's touch.
                                if (!isInside(change.position)) break
                                val point = pointAt(change.position)
                                if (erasing) {
                                    eraserPosition = point
                                    update(working.eraseBetween(lastPoint, point, ERASER_RADIUS))
                                    lastPoint = point
                                } else if (point != lastPoint &&
                                    (!change.pressed || point.isFarEnoughFrom(lastPoint, POINT_SPACING))
                                ) {
                                    if (pointCount >= Whiteboard.MAX_POINTS) {
                                        limitReached = true
                                        break
                                    }
                                    val stroke = working.strokes.last()
                                    update(working.copy(strokes = working.strokes.dropLast(1) + stroke.copy(points = stroke.points + point)))
                                    pointCount++
                                    lastPoint = point
                                }
                                if (!change.pressed) break
                            }
                        } finally {
                            drawing = false
                            eraserPosition = null
                        }
                    }
                },
        ) {
            drawWhiteboard(whiteboard)
            eraserPosition?.let { point ->
                val scale = size.width / Whiteboard.CANVAS_SIZE
                drawCircle(
                    Color(0xFF5F6368),
                    radius = ERASER_RADIUS * scale,
                    center = Offset(point.x * scale, point.y * scale),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        if (!readOnly) {
            Text(
                if (erasing) "橡皮会擦除整条笔迹；在画布外滑动可滚动页面。" else "用手指或触控笔绘画；在画布外滑动可滚动页面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (limitReached || whiteboard.strokes.size >= Whiteboard.MAX_STROKES ||
                whiteboard.strokes.sumOf { it.points.size } >= Whiteboard.MAX_POINTS
            ) {
                Text(
                    "白板已达到容量上限，请撤销或擦除部分笔迹后继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    if (showClearDialog && !readOnly) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空白板？") },
            text = { Text("所有笔迹都会清除，本次编辑中可以撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    rememberUndo(currentBoard)
                    limitReached = false
                    publish(Whiteboard())
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun WhiteboardPreview(whiteboard: Whiteboard, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .semantics { contentDescription = "白板预览" },
    ) {
        drawWhiteboard(whiteboard)
    }
}

private fun DrawScope.drawWhiteboard(whiteboard: Whiteboard) {
    val scale = size.width / Whiteboard.CANVAS_SIZE
    whiteboard.strokes.forEach { stroke ->
        val first = stroke.points.firstOrNull() ?: return@forEach
        val color = Color(stroke.color)
        val width = stroke.width * scale
        if (stroke.points.size == 1) {
            drawCircle(color, width / 2f, Offset(first.x * scale, first.y * scale))
        } else {
            val path = Path().apply {
                moveTo(first.x * scale, first.y * scale)
                for (index in 1 until stroke.points.size) {
                    val point = stroke.points[index]
                    lineTo(point.x * scale, point.y * scale)
                }
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
