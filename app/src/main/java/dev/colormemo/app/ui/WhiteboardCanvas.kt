package dev.colormemo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import dev.colormemo.app.R
import dev.colormemo.app.data.inkLayers
import dev.colormemo.core.model.WhiteboardBrush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.model.NoteAttachment
import kotlin.math.roundToInt

private const val HISTORY_LIMIT = 25
private const val ERASER_RADIUS = 28f

private enum class WhiteboardTool { PEN, ERASER }

private val ViewportSaver = listSaver<WhiteboardViewport, Float>(
    save = { listOf(it.centerX, it.centerY, it.zoom) },
    restore = { WhiteboardViewport(it[0], it[1], it[2]) },
)

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
    attachments: List<NoteAttachment> = emptyList(),
    imageToInsert: WhiteboardImage? = null,
    onImageInserted: () -> Unit = {},
) {
    val currentBoard by rememberUpdatedState(whiteboard)
    val onChange by rememberUpdatedState(onWhiteboardChange)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var ink by rememberSaveable { mutableStateOf(inkColors.first().first) }
    var lineWidth by rememberSaveable { mutableStateOf(3f) }
    var brush by rememberSaveable { mutableStateOf(WhiteboardBrush.FINELINER) }
    var tool by rememberSaveable { mutableStateOf(WhiteboardTool.PEN) }
    var showBrushSettings by rememberSaveable { mutableStateOf(false) }
    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf(WhiteboardViewport()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val erasing = tool == WhiteboardTool.ERASER
    var drawing by remember { mutableStateOf(false) }
    var eraserPosition by remember { mutableStateOf<WhiteboardPoint?>(null) }
    var undoBoards by remember { mutableStateOf(emptyList<Whiteboard>()) }
    var redoBoards by remember { mutableStateOf(emptyList<Whiteboard>()) }
    var lastPublished by remember { mutableStateOf(whiteboard) }
    var showClearDialog by remember { mutableStateOf(false) }
    var limitReached by remember { mutableStateOf(false) }
    val controlsEnabled = !readOnly && !drawing
    val imageBitmaps = rememberWhiteboardImages(whiteboard, attachments, 1200)

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
        if (readOnly) {
            showClearDialog = false
            showBrushSettings = false
        }
    }
    LaunchedEffect(imageToInsert, canvasSize, readOnly) {
        if (imageToInsert != null && !readOnly && canvasSize.width > 0 && canvasSize.height > 0) {
            val image = imageToInsert.placedIn(viewport.transform(canvasSize.width.toFloat(), canvasSize.height.toFloat()))
            rememberUndo(currentBoard)
            publish(currentBoard.copy(images = currentBoard.images + image))
            tool = WhiteboardTool.PEN
            onImageInserted()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!readOnly) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconToggleButton(checked = !erasing, onCheckedChange = { tool = WhiteboardTool.PEN }, enabled = controlsEnabled) {
                    Icon(Icons.Outlined.Draw, "画笔")
                }
                IconToggleButton(checked = erasing, onCheckedChange = { tool = WhiteboardTool.ERASER }, enabled = controlsEnabled) {
                    Icon(painterResource(R.drawable.ic_eraser), "橡皮")
                }
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
                IconButton(onClick = { showBrushSettings = true }, enabled = controlsEnabled) {
                    Icon(Icons.Outlined.Tune, "画笔类型、颜色与粗细")
                }
                IconButton(
                    onClick = { showClearDialog = true },
                    enabled = controlsEnabled && (whiteboard.strokes.isNotEmpty() || whiteboard.images.isNotEmpty()),
                ) { Icon(Icons.Outlined.DeleteOutline, "清空白板") }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .onSizeChanged {
                        canvasSize = it
                        if (it.width > 0 && it.height > 0) {
                            viewport = viewport.constrained(it.width.toFloat(), it.height.toFloat())
                        }
                    }
                    .semantics {
                        contentDescription = when {
                            readOnly -> "白板预览，可拖动和双指缩放"
                            else -> "白板画布，单指绘画或擦除，双指拖动和缩放"
                        }
                    }
                    .pointerInput(readOnly, ink, lineWidth, brush, tool, canvasSize) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Initial)
                            val width = size.width.toFloat()
                            val height = size.height.toFloat()
                            if (width <= 0f || height <= 0f) return@awaitEachGesture
                            val singleFingerNavigation = readOnly
                            var navigating = singleFingerNavigation || currentEvent.changes.count { it.pressed } > 1
                            currentEvent.changes.forEach { it.consume() }
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            val gesture = WhiteboardGestureSession(currentBoard, erasing, ink, lineWidth, brush)
                            // A stroke keeps one transform; navigation takes over for the rest
                            // of this touch sequence as soon as another finger arrives.
                            val transform = viewport.transform(width, height)
                            fun pointAt(position: Offset): WhiteboardPoint {
                                val point = transform.screenToWorld(position.x, position.y)
                                return whiteboardPoint(point.x, point.y)
                            }
                            var strokeStopped = !transform.containsScreenPoint(down.position.x, down.position.y)
                            fun showWorkingBoard() {
                                if (gesture.working != lastPublished) publish(gesture.working)
                                limitReached = gesture.limitReached
                            }

                            drawing = true
                            try {
                                if (navigating) {
                                    gesture.startNavigation()
                                } else if (!strokeStopped) {
                                    val point = pointAt(down.position)
                                    gesture.begin(point)
                                    if (erasing) eraserPosition = point
                                    showWorkingBoard()
                                }
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.isConsumed }) break
                                    if (!navigating && event.changes.count { it.pressed } > 1) {
                                        navigating = true
                                        // Remove the first finger's tentative dot/stroke/erasure.
                                        // Navigation never creates undo entries or clears redo.
                                        gesture.startNavigation()
                                        eraserPosition = null
                                        showWorkingBoard()
                                    }
                                    if (navigating) {
                                        val stablePointers = event.changes.count { it.pressed && it.previousPressed }
                                        if (stablePointers >= if (singleFingerNavigation) 1 else 2) {
                                            val centroid = event.calculateCentroid(useCurrent = false)
                                            val pan = event.calculatePan()
                                            viewport = viewport.applyGesture(
                                                width, height, centroid.x, centroid.y,
                                                panX = pan.x, panY = pan.y, zoomChange = event.calculateZoom(),
                                            )
                                        }
                                    } else if (!strokeStopped) {
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !transform.containsScreenPoint(change.position.x, change.position.y)) {
                                            // Do not join separated pieces if a finger leaves and re-enters.
                                            strokeStopped = true
                                            eraserPosition = null
                                        } else {
                                            val point = pointAt(change.position)
                                            gesture.update(point, finalPoint = !change.pressed)
                                            if (erasing) eraserPosition = point
                                            showWorkingBoard()
                                            if (!change.pressed) strokeStopped = true
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            } finally {
                                // Cancellation preserves already published ink, including when
                                // Save or rotation interrupts the pointer coroutine.
                                if (gesture.hasChanges) rememberUndo(gesture.before)
                                drawing = false
                                eraserPosition = null
                            }
                        }
                    },
            ) {
                if (size.width > 0f && size.height > 0f) {
                    val transform = viewport.transform(size.width, size.height)
                    val origin = Offset(transform.originX, transform.originY)
                    val boardWidth = transform.canvasWidthPx
                    val boardHeight = transform.canvasHeightPx
                    clipRect(origin.x, origin.y, origin.x + boardWidth, origin.y + boardHeight) {
                        drawRect(Color.White, origin, Size(boardWidth, boardHeight))
                        // Subtle guide dots make movement visible even on an empty board.
                        val gridStep = 50f * transform.scale
                        for (x in 0..(Whiteboard.CANVAS_WIDTH / 50f).toInt()) for (y in 0..(Whiteboard.CANVAS_HEIGHT / 50f).toInt()) {
                            drawCircle(Color(0xFFE0E3E7), 0.7.dp.toPx(), origin + Offset(x * gridStep, y * gridStep))
                        }
                        drawWhiteboard(whiteboard, transform.scale, origin, imageBitmaps)
                        eraserPosition?.let { point ->
                            drawCircle(
                                Color(0xFF5F6368),
                                radius = ERASER_RADIUS * transform.scale,
                                center = origin + Offset(point.x * transform.scale, point.y * transform.scale),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
            // Keep this overlay out of measurement: changing the canvas size here
            // would cancel the last allowed stroke or the first eraser movement.
            if (!readOnly && (limitReached || whiteboard.strokes.size >= Whiteboard.MAX_STROKES ||
                whiteboard.strokes.sumOf { it.points.size } >= Whiteboard.MAX_POINTS)
            ) {
                Text(
                    "白板已达到容量上限，请撤销或擦除部分笔迹后继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    if (showBrushSettings && !readOnly) {
        AlertDialog(
            onDismissRequest = { showBrushSettings = false },
            title = { Text("画笔设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("笔型", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WhiteboardBrush.entries.forEach { type ->
                            FilterChip(
                                selected = brush == type,
                                onClick = {
                                    brush = type
                                    lineWidth = when (type) {
                                        WhiteboardBrush.FINELINER -> 3f
                                        WhiteboardBrush.WATERCOLOR -> 24f
                                        WhiteboardBrush.MARKER -> 16f
                                    }
                                    tool = WhiteboardTool.PEN
                                },
                                label = { Text(type.displayName) },
                            )
                        }
                    }
                    Text("颜色", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        inkColors.forEach { (color, label) ->
                            val selected = ink == color
                            Box(
                                Modifier.size(48.dp).clip(CircleShape)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { ink = color; tool = WhiteboardTool.PEN })
                                    .semantics { contentDescription = "${label}画笔" }
                                    .padding(5.dp)
                                    .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .padding(3.dp).background(Color(color), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = Color.White)
                            }
                        }
                    }
                    Text("笔触粗细", style = MaterialTheme.typography.labelLarge)
                    listOf(1f, 3f, 6f, 10f, 16f, 24f, 32f, 40f).chunked(4).forEachIndexed { rowIndex, widths ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            widths.forEachIndexed { columnIndex, width ->
                                val selected = lineWidth == width
                                Box(
                                    Modifier.size(52.dp).clip(CircleShape)
                                        .selectable(selected, role = Role.RadioButton, onClick = { lineWidth = width; tool = WhiteboardTool.PEN })
                                        .semantics { contentDescription = "粗细第${rowIndex * 4 + columnIndex + 1}档，共8档" }
                                        .padding(3.dp)
                                        .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.size((4f + width * 0.7f).dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                                }
                            }
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(64.dp).clip(MaterialTheme.shapes.small).background(Color.White)) {
                        val sample = Whiteboard(listOf(dev.colormemo.core.model.WhiteboardStroke(
                            color = ink, width = lineWidth,
                            points = listOf(WhiteboardPoint(100f, 500f), WhiteboardPoint(500f, 450f), WhiteboardPoint(900f, 500f)),
                            brush = brush,
                        )))
                        val scale = size.width / Whiteboard.CANVAS_WIDTH
                        drawWhiteboard(sample, scale, Offset(0f, size.height / 2f - 475f * scale))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBrushSettings = false }) { Text("完成") } },
        )
    }

    if (showClearDialog && !readOnly) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空白板？") },
            text = { Text("所有笔迹和图片都会清除，本次编辑中可以撤销。") },
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
internal fun WhiteboardPreview(whiteboard: Whiteboard, modifier: Modifier = Modifier, attachments: List<NoteAttachment> = emptyList()) {
    val imageBitmaps = rememberWhiteboardImages(whiteboard, attachments, 384)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(Whiteboard.CANVAS_WIDTH / Whiteboard.CANVAS_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .semantics { contentDescription = "白板预览" },
    ) {
        drawWhiteboard(whiteboard, imageBitmaps = imageBitmaps)
    }
}

private fun DrawScope.drawWhiteboard(
    whiteboard: Whiteboard,
    scale: Float = size.width / Whiteboard.CANVAS_WIDTH,
    origin: Offset = Offset.Zero,
    imageBitmaps: Map<String, ImageBitmap> = emptyMap(),
) {
    whiteboard.images.forEach { image ->
        val offset = origin + Offset(image.x * scale, image.y * scale)
        val imageSize = Size(image.width * scale, image.height * scale)
        val bitmap = imageBitmaps[image.attachmentId]
        if (bitmap != null) {
            drawImage(bitmap, dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                dstSize = IntSize(imageSize.width.roundToInt().coerceAtLeast(1), imageSize.height.roundToInt().coerceAtLeast(1)))
        } else {
            drawRect(Color(0xFFE0E3E7), offset, imageSize)
        }
    }
    whiteboard.strokes.forEach { stroke ->
        val first = stroke.points.firstOrNull() ?: return@forEach
        val path = Path().apply {
            moveTo(origin.x + first.x * scale, origin.y + first.y * scale)
            for (index in 1 until stroke.points.size) {
                val point = stroke.points[index]
                lineTo(origin.x + point.x * scale, origin.y + point.y * scale)
            }
        }
        val marker = stroke.brush == WhiteboardBrush.MARKER
        val isDot = stroke.points.all { it == first }
        stroke.brush.inkLayers.forEach { layer ->
            val color = Color(stroke.color).copy(alpha = layer.opacity)
            val width = stroke.width * scale * layer.widthScale
            val center = origin + Offset(first.x * scale, first.y * scale)
            if (isDot) {
                if (marker) drawRect(color, center - Offset(width / 2f, width / 2f), Size(width, width))
                else drawCircle(color, width / 2f, center)
            } else {
                drawPath(path, color, style = Stroke(width,
                    cap = if (marker) StrokeCap.Square else StrokeCap.Round,
                    join = if (marker) StrokeJoin.Bevel else StrokeJoin.Round))
            }
        }
    }
}

private val WhiteboardBrush.displayName: String
    get() = when (this) {
        WhiteboardBrush.FINELINER -> "细签字笔"
        WhiteboardBrush.WATERCOLOR -> "水彩笔"
        WhiteboardBrush.MARKER -> "马克笔"
    }
