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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import dev.colormemo.core.model.WhiteboardText
import dev.colormemo.core.model.NoteAttachment
import kotlin.math.roundToInt

private const val HISTORY_LIMIT = 25

private enum class WhiteboardTool { PEN, SHAPE, ERASER, INSTRUMENT, TEXT }

private val ViewportSaver = listSaver<WhiteboardViewport, Float>(
    save = { listOf(it.centerX, it.centerY, it.zoom) },
    restore = { WhiteboardViewport(it[0], it[1], it[2]) },
)

private val InstrumentSaver = listSaver<WhiteboardInstrumentState, Float>(
    save = { listOf(it.centerX, it.centerY, it.radius, it.baselineDegrees, it.angleDegrees) },
    restore = { WhiteboardInstrumentState(it[0], it[1], it[2], it[3], it[4]).constrained() },
)

private val inkColors = listOf(
    0xFF202124L to "黑色",
    0xFF5F6368L to "深灰",
    0xFF9AA0A6L to "灰色",
    0xFFDADCE0L to "浅灰",
    0xFFFFFFFFL to "白色",
    0xFFA50E0EL to "深红",
    0xFFD93025L to "红色",
    0xFFF28B82L to "珊瑚红",
    0xFFE8710AL to "橙色",
    0xFFF9AB00L to "琥珀色",
    0xFFFDD663L to "黄色",
    0xFF0D652DL to "深绿",
    0xFF188038L to "绿色",
    0xFF81C995L to "薄荷绿",
    0xFF007B83L to "青绿",
    0xFF12B5CBL to "青色",
    0xFFA1E4F2L to "浅青",
    0xFF174EA6L to "深蓝",
    0xFF1967D2L to "蓝色",
    0xFF8AB4F8L to "天蓝",
    0xFF3F51B5L to "靛蓝",
    0xFF8430CEL to "紫色",
    0xFFD01884L to "粉色",
    0xFF795548L to "棕色",
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
    var shape by rememberSaveable { mutableStateOf(WhiteboardShape.RECTANGLE) }
    var instrument by rememberSaveable { mutableStateOf(WhiteboardInstrument.COMPASS) }
    var instrumentState by rememberSaveable(stateSaver = InstrumentSaver) { mutableStateOf(WhiteboardInstrumentState()) }
    var instrumentPlaced by rememberSaveable { mutableStateOf(false) }
    var showGuides by rememberSaveable { mutableStateOf(false) }
    var showTextEditor by rememberSaveable { mutableStateOf(false) }
    var editingTextId by rememberSaveable { mutableStateOf<String?>(null) }
    var textPositionX by rememberSaveable { mutableStateOf(500f) }
    var textPositionY by rememberSaveable { mutableStateOf(500f) }
    var showShapePicker by rememberSaveable { mutableStateOf(false) }
    var showBrushSettings by rememberSaveable { mutableStateOf(false) }
    var showCustomInk by rememberSaveable { mutableStateOf(false) }
    var eraserRadius by rememberSaveable { mutableStateOf(DEFAULT_ERASER_RADIUS) }
    var showEraserSettings by rememberSaveable { mutableStateOf(false) }
    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf(WhiteboardViewport()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val erasing = tool == WhiteboardTool.ERASER
    var drawing by remember { mutableStateOf(false) }
    var eraserPosition by remember { mutableStateOf<WhiteboardPoint?>(null) }
    var guidePoint by remember { mutableStateOf<WhiteboardPoint?>(null) }
    var guideAnchor by remember { mutableStateOf<WhiteboardPoint?>(null) }
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

    fun visibleInstrumentState(): WhiteboardInstrumentState {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return WhiteboardInstrumentState()
        val transform = viewport.transform(canvasSize.width.toFloat(), canvasSize.height.toFloat())
        val first = transform.screenToWorld(0f, 0f)
        val last = transform.screenToWorld(canvasSize.width.toFloat(), canvasSize.height.toFloat())
        val left = first.x.coerceIn(0f, Whiteboard.CANVAS_WIDTH)
        val right = last.x.coerceIn(0f, Whiteboard.CANVAS_WIDTH)
        val top = first.y.coerceIn(0f, Whiteboard.CANVAS_HEIGHT)
        val bottom = last.y.coerceIn(0f, Whiteboard.CANVAS_HEIGHT)
        return WhiteboardInstrumentState((left + right) / 2f, (top + bottom) / 2f,
            minOf(180f, (right - left) * 0.3f, (bottom - top) * 0.3f).coerceAtLeast(MIN_INSTRUMENT_RADIUS)).constrained()
    }

    fun openTextEditor(text: WhiteboardText?, point: WhiteboardPoint = visibleInstrumentState().center) {
        if (text == null && currentBoard.texts.size >= Whiteboard.MAX_TEXTS) {
            limitReached = true
            return
        }
        editingTextId = text?.id
        textPositionX = point.x
        textPositionY = point.y
        showTextEditor = true
        limitReached = false
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
            showShapePicker = false
            showBrushSettings = false
            showEraserSettings = false
            showTextEditor = false
            showCustomInk = false
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
                IconToggleButton(checked = tool == WhiteboardTool.PEN, onCheckedChange = {
                    tool = WhiteboardTool.PEN
                    showBrushSettings = true
                }, enabled = controlsEnabled) {
                    Icon(Icons.Outlined.Draw, "画笔，设置笔型、颜色与粗细")
                }
                Box {
                    IconToggleButton(
                        checked = tool == WhiteboardTool.SHAPE || tool == WhiteboardTool.INSTRUMENT,
                        onCheckedChange = { showShapePicker = true },
                        enabled = controlsEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "绘图工具：${if (tool == WhiteboardTool.INSTRUMENT) instrument.displayName else shape.displayName}，选择图形与辅助工具"
                        },
                    ) {
                        if (tool == WhiteboardTool.INSTRUMENT) WhiteboardInstrumentIcon(instrument) else WhiteboardShapeIcon(shape)
                    }
                    DropdownMenu(expanded = showShapePicker && controlsEnabled, onDismissRequest = { showShapePicker = false }) {
                        Text("图形", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                        WhiteboardShape.entries.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.displayName) },
                                leadingIcon = { WhiteboardShapeIcon(template) },
                                trailingIcon = {
                                    if (tool == WhiteboardTool.SHAPE && shape == template) Icon(Icons.Outlined.Check, "已选中")
                                },
                                onClick = {
                                    shape = template
                                    tool = WhiteboardTool.SHAPE
                                    showShapePicker = false
                                    limitReached = false
                                },
                            )
                        }
                        HorizontalDivider()
                        Text("辅助工具", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                        WhiteboardInstrument.entries.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.displayName) },
                                leadingIcon = { WhiteboardInstrumentIcon(template) },
                                trailingIcon = {
                                    if (tool == WhiteboardTool.INSTRUMENT && instrument == template) Icon(Icons.Outlined.Check, "已选中")
                                },
                                onClick = {
                                    if (!instrumentPlaced) {
                                        instrumentState = visibleInstrumentState()
                                        instrumentPlaced = true
                                    }
                                    instrument = template
                                    tool = WhiteboardTool.INSTRUMENT
                                    showShapePicker = false
                                    limitReached = false
                                },
                            )
                        }
                    }
                }
                IconToggleButton(checked = tool == WhiteboardTool.TEXT, onCheckedChange = {
                    tool = WhiteboardTool.TEXT
                    openTextEditor(null)
                }, enabled = controlsEnabled) { Icon(Icons.Outlined.TextFields, "文字，插入或编辑白板文字") }
                IconToggleButton(checked = showGuides, onCheckedChange = { showGuides = it }, enabled = controlsEnabled) {
                    Icon(Icons.Outlined.GridOn, "显示辅助线")
                }
                IconToggleButton(checked = erasing, onCheckedChange = {
                    if (erasing) showEraserSettings = true else tool = WhiteboardTool.ERASER
                }, enabled = controlsEnabled) {
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
                IconButton(
                    onClick = { showClearDialog = true },
                    enabled = controlsEnabled && whiteboard.hasContent,
                ) { Icon(Icons.Outlined.DeleteOutline, "清空白板") }
            }
            if (tool == WhiteboardTool.SHAPE) {
                Text("${shape.displayName}：在画布上拖动绘制，松手完成，颜色跟随画笔", Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (tool == WhiteboardTool.INSTRUMENT) {
                WhiteboardInstrumentControls(instrument, instrumentState, controlsEnabled,
                    onChange = { instrumentState = it },
                    onDraw = {
                        val next = currentBoard.withInstrumentDrawing(instrument, instrumentState, ink, lineWidth, brush)
                        limitReached = next == null
                        if (next != null) {
                            rememberUndo(currentBoard)
                            publish(next)
                        }
                    }, onClose = { tool = WhiteboardTool.PEN })
            } else if (tool == WhiteboardTool.TEXT) {
                Text("轻点空白处插入文字，拖动文字移动，轻点文字编辑；再次点击文字按钮可添加文字。",
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            tool == WhiteboardTool.SHAPE -> "白板画布，单指拖动绘制${shape.displayName}，双指拖动和缩放"
                            tool == WhiteboardTool.INSTRUMENT -> "白板画布，调节${instrument.displayName}，双指拖动和缩放"
                            tool == WhiteboardTool.TEXT -> "白板画布，轻点插入或编辑文字，拖动文字移动，双指拖动和缩放"
                            erasing -> "白板画布，单指擦除，双指拖动和缩放"
                            else -> "白板画布，单指绘画或擦除，双指拖动和缩放"
                        }
                    }
                    .pointerInput(readOnly, ink, lineWidth, brush, tool, shape, instrument, eraserRadius, canvasSize) {
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
                            val gesture = WhiteboardGestureSession(currentBoard, erasing, ink, lineWidth, brush,
                                shape = shape.takeIf { tool == WhiteboardTool.SHAPE }, eraserRadius = eraserRadius)
                            // A stroke keeps one transform; navigation takes over for the rest
                            // of this touch sequence as soon as another finger arrives.
                            val transform = viewport.transform(width, height)
                            fun pointAt(position: Offset): WhiteboardPoint {
                                val point = transform.screenToWorld(position.x, position.y)
                                return whiteboardPoint(point.x, point.y)
                            }
                            var strokeStopped = !transform.containsScreenPoint(down.position.x, down.position.y)
                            val downPoint = pointAt(down.position)
                            val instrumentDrag = if (!readOnly && tool == WhiteboardTool.INSTRUMENT && !strokeStopped) {
                                WhiteboardInstrumentDrag(instrumentState, instrument, downPoint, 24.dp.toPx() / transform.scale)
                            } else null
                            val textHit = if (!readOnly && tool == WhiteboardTool.TEXT && !strokeStopped) {
                                currentBoard.textAt(downPoint, 8.dp.toPx() / transform.scale)
                            } else null
                            val textDrag = textHit?.let { WhiteboardTextDrag(currentBoard, it, downPoint, viewConfiguration.touchSlop / transform.scale) }
                            var completedTextTap = false
                            var pointerTravelled = false
                            val touchSlop = viewConfiguration.touchSlop / transform.scale
                            fun showWorkingBoard() {
                                if (gesture.working != lastPublished) publish(gesture.working)
                                limitReached = gesture.limitReached
                            }

                            drawing = true
                            try {
                                if (navigating) {
                                    gesture.startNavigation()
                                } else if (!strokeStopped) {
                                    when (tool) {
                                        WhiteboardTool.INSTRUMENT -> instrumentDrag?.let { instrumentState = it.update(downPoint) }
                                        WhiteboardTool.TEXT -> Unit
                                        else -> {
                                            gesture.begin(downPoint)
                                            guidePoint = downPoint
                                            guideAnchor = downPoint.takeIf { tool == WhiteboardTool.SHAPE }
                                            if (erasing) eraserPosition = downPoint
                                            showWorkingBoard()
                                        }
                                    }
                                }
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.isConsumed }) break
                                    if (!navigating && event.changes.count { it.pressed } > 1) {
                                        navigating = true
                                        // Remove the first finger's tentative dot/stroke/erasure.
                                        // Navigation never creates undo entries or clears redo.
                                        gesture.startNavigation()
                                        instrumentDrag?.let { instrumentState = it.startNavigation() }
                                        textDrag?.startNavigation()
                                        eraserPosition = null
                                        guidePoint = null
                                        guideAnchor = null
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
                                            guidePoint = null
                                        } else {
                                            val point = pointAt(change.position)
                                            when (tool) {
                                                WhiteboardTool.INSTRUMENT -> instrumentDrag?.let { instrumentState = it.update(point) }
                                                WhiteboardTool.TEXT -> {
                                                    if (point.isFarEnoughFrom(downPoint, touchSlop)) pointerTravelled = true
                                                    textDrag?.let {
                                                        val next = it.update(point)
                                                        if (next != lastPublished) publish(next)
                                                    }
                                                    if (!change.pressed) completedTextTap = !pointerTravelled && textDrag?.hasMoved != true
                                                }
                                                else -> {
                                                    gesture.update(point, finalPoint = !change.pressed)
                                                    guidePoint = point
                                                    if (erasing) eraserPosition = point
                                                    showWorkingBoard()
                                                }
                                            }
                                            if (!change.pressed) strokeStopped = true
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            } finally {
                                // Cancellation preserves already published ink, including when
                                // Save or rotation interrupts the pointer coroutine.
                                if (gesture.hasChanges) rememberUndo(gesture.before)
                                if (textDrag?.hasChanges == true) rememberUndo(textDrag.before)
                                drawing = false
                                eraserPosition = null
                                guidePoint = null
                                guideAnchor = null
                                if (completedTextTap && !navigating) openTextEditor(textHit, downPoint)
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
                        val gridStep = 50f * transform.scale
                        if (showGuides && !readOnly) {
                            val gridColor = Color(0xFFE0E3E7)
                            for (x in 0..(Whiteboard.CANVAS_WIDTH / 50f).toInt()) {
                                val offset = origin + Offset(x * gridStep, 0f)
                                drawLine(gridColor, offset, offset + Offset(0f, boardHeight), 0.7.dp.toPx())
                            }
                            for (y in 0..(Whiteboard.CANVAS_HEIGHT / 50f).toInt()) {
                                val offset = origin + Offset(0f, y * gridStep)
                                drawLine(gridColor, offset, offset + Offset(boardWidth, 0f), 0.7.dp.toPx())
                            }
                        } else {
                            // Dots make navigation visible even with the line guides hidden.
                            for (x in 0..(Whiteboard.CANVAS_WIDTH / 50f).toInt()) for (y in 0..(Whiteboard.CANVAS_HEIGHT / 50f).toInt()) {
                                drawCircle(Color(0xFFE0E3E7), 0.7.dp.toPx(), origin + Offset(x * gridStep, y * gridStep))
                            }
                        }
                        drawWhiteboard(whiteboard, transform.scale, origin, imageBitmaps)
                        if (showGuides && !readOnly) guidePoint?.let { point ->
                            val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                            val color = Color(0xFF1967D2).copy(alpha = 0.55f)
                            val position = origin + Offset(point.x * transform.scale, point.y * transform.scale)
                            drawLine(color, Offset(position.x, origin.y), Offset(position.x, origin.y + boardHeight), 1.dp.toPx(), pathEffect = dashed)
                            drawLine(color, Offset(origin.x, position.y), Offset(origin.x + boardWidth, position.y), 1.dp.toPx(), pathEffect = dashed)
                            guideAnchor?.let { anchor ->
                                val left = minOf(anchor.x, point.x)
                                val top = minOf(anchor.y, point.y)
                                drawRect(color, origin + Offset(left * transform.scale, top * transform.scale),
                                    Size(kotlin.math.abs(point.x - anchor.x) * transform.scale, kotlin.math.abs(point.y - anchor.y) * transform.scale),
                                    style = Stroke(1.dp.toPx(), pathEffect = dashed))
                            }
                        }
                        if (!readOnly && tool == WhiteboardTool.TEXT) whiteboard.texts.forEach { text ->
                            drawRect(Color(0xFF1967D2).copy(alpha = 0.6f), origin + Offset(text.x * transform.scale, text.y * transform.scale),
                                Size(text.width * transform.scale, text.height * transform.scale),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
                        }
                        if (!readOnly && tool == WhiteboardTool.INSTRUMENT) drawWhiteboardInstrument(instrument, instrumentState, transform)
                        eraserPosition?.let { point ->
                            drawCircle(
                                Color(0xFF5F6368),
                                radius = eraserRadius * transform.scale,
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
                    if (tool == WhiteboardTool.TEXT && whiteboard.texts.size >= Whiteboard.MAX_TEXTS) "单张白板最多插入 100 段文字，请编辑或删除已有文字。"
                    else if (erasing && limitReached) "擦除后的笔迹片段过多，请扩大橡皮范围或撤销部分笔迹。"
                    else "白板已达到容量上限，请撤销或擦除部分笔迹后继续。",
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

    if (showTextEditor && !readOnly) {
        WhiteboardTextEditor(
            existing = currentBoard.texts.firstOrNull { it.id == editingTextId },
            position = WhiteboardPoint(textPositionX, textPositionY), initialColor = ink,
            onSave = { text ->
                val existing = currentBoard.texts.any { it.id == text.id }
                if (existing || currentBoard.texts.size < Whiteboard.MAX_TEXTS) {
                    val next = currentBoard.copy(texts = if (existing) currentBoard.texts.map { if (it.id == text.id) text else it }
                        else currentBoard.texts + text)
                    if (next != currentBoard) {
                        rememberUndo(currentBoard)
                        publish(next)
                    }
                } else limitReached = true
                showTextEditor = false
            }, onDelete = {
                val next = currentBoard.copy(texts = currentBoard.texts.filterNot { it.id == editingTextId })
                if (next != currentBoard) {
                    rememberUndo(currentBoard)
                    publish(next)
                }
                showTextEditor = false
            }, onDismiss = { showTextEditor = false },
        )
    }

    if (showBrushSettings && !readOnly) {
        AlertDialog(
            onDismissRequest = { showBrushSettings = false },
            title = { Text("画笔设置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                },
                                label = { Text(type.displayName) },
                            )
                        }
                    }
                    Text("颜色 #${(ink and 0xFFFFFFL).toString(16).padStart(6, '0').uppercase()}", style = MaterialTheme.typography.labelLarge)
                    WhiteboardInkColorPicker(ink, onColorChange = { ink = it })
                    TextButton(onClick = { showCustomInk = true }) { Text("自定义颜色") }
                    Text("笔触粗细", style = MaterialTheme.typography.labelLarge)
                    listOf(1f, 3f, 6f, 10f, 16f, 24f, 32f, 40f).chunked(4).forEachIndexed { rowIndex, widths ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            widths.forEachIndexed { columnIndex, width ->
                                val selected = lineWidth == width
                                Box(
                                    Modifier.size(52.dp).clip(CircleShape)
                                        .selectable(selected, role = Role.RadioButton, onClick = { lineWidth = width })
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

    if (showCustomInk && !readOnly) {
        WhiteboardCustomColorEditor(ink, onSave = { ink = it; showCustomInk = false }, onDismiss = { showCustomInk = false })
    }

    if (showEraserSettings && !readOnly) {
        AlertDialog(
            onDismissRequest = { showEraserSettings = false },
            title = { Text("橡皮设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("拖动调整擦除范围。仅擦除扫过的局部笔迹，保留其余笔画和图片。")
                    Canvas(Modifier.fillMaxWidth().height(140.dp).clip(MaterialTheme.shapes.small).background(Color.White)) {
                        val radius = eraserRadius / MAX_ERASER_RADIUS * (size.minDimension / 2f - 8.dp.toPx())
                        drawCircle(Color(0x145F6368), radius)
                        drawCircle(Color(0xFF5F6368), radius, style = Stroke(1.5.dp.toPx()))
                    }
                    Slider(
                        value = eraserRadius,
                        onValueChange = { eraserRadius = it },
                        valueRange = MIN_ERASER_RADIUS..MAX_ERASER_RADIUS,
                        steps = 22,
                        modifier = Modifier.semantics { contentDescription = "橡皮擦除范围" },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("小")
                        Text("大")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEraserSettings = false }) { Text("完成") } },
        )
    }

    if (showClearDialog && !readOnly) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空白板？") },
            text = { Text("所有笔迹、文字和图片都会清除，本次编辑中可以撤销。") },
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
internal fun WhiteboardInkColorPicker(ink: Long, onColorChange: (Long) -> Unit) {
    Column {
        inkColors.chunked(5).forEach { colors ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                colors.forEach { (color, label) ->
                    val selected = ink == color
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onColorChange(color) })
                            .semantics { contentDescription = "${label}笔触" }
                            .padding(5.dp)
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .padding(3.dp).background(Color(color), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp),
                            tint = if (Color(color).luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun WhiteboardCustomColorEditor(ink: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf((ink and 0xFFFFFFL).toString(16).padStart(6, '0').uppercase()) }
    val valid = value.length == 6 && value.all { it in '0'..'9' || it in 'A'..'F' }
    val parsed = if (valid) 0xFF000000L or value.toLong(16) else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义画笔颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value, onValueChange = { value = it.removePrefix("#").take(6).uppercase() },
                    label = { Text("六位色值，例如 FF5722") }, prefix = { Text("#") }, singleLine = true,
                    isError = !valid, modifier = Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color(parsed ?: ink), MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small))
            }
        }, confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("使用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun WhiteboardShapeIcon(shape: WhiteboardShape) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val open = shape == WhiteboardShape.LINE || shape.isArrow
        val start = when {
            open -> WhiteboardPoint(140f, 780f)
            shape in listOf(WhiteboardShape.CIRCLE, WhiteboardShape.SQUARE, WhiteboardShape.CUBE, WhiteboardShape.SPHERE) -> WhiteboardPoint(220f, 220f)
            else -> WhiteboardPoint(140f, 220f)
        }
        val end = if (open) WhiteboardPoint(860f, 220f) else WhiteboardPoint(860f, 780f)
        // Enlarge arrowheads so the template remains recognisable at icon size.
        val points = shape.points(start, end, 80f)
        val path = Path().apply {
            points.forEachIndexed { index, point ->
                val x = point.x / 1000f * size.width
                val y = point.y / 1000f * size.height
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, tint, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
    drawWhiteboardTexts(whiteboard.texts, scale, origin)
}

private val WhiteboardBrush.displayName: String
    get() = when (this) {
        WhiteboardBrush.FINELINER -> "签字笔"
        WhiteboardBrush.WATERCOLOR -> "水彩笔"
        WhiteboardBrush.MARKER -> "马克笔"
    }
