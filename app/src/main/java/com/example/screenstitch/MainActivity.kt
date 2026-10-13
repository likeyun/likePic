package com.example.screenstitch

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private data class Photo(val uri: Uri, val bitmap: Bitmap, var visible: Boolean = true, var shift: Float = 0f)
    private data class GalleryPhoto(val uri: Uri, val bucketId: String, val bucketName: String, val dateAdded: Long)
    private data class GalleryAlbum(val id: String?, val name: String, val count: Int, val cover: Uri?)
    private data class GalleryHolder(val image: ImageView, val overlay: View, val badge: TextView)
    private data class RemoteAppVersion(
        val version: String,
        val versionCode: Long,
        val name: String,
        val description: String,
        val downloadUrl: String,
        val forceUpdate: Boolean,
        val publishTime: String
    )
    private data class PhotoState(val photo: Photo, val visible: Boolean, val shift: Float)
    private data class EditSnapshot(
        val photos: List<PhotoState>, val seamLocks: List<Boolean>,
        val leftEdgeLocked: Boolean, val rightEdgeLocked: Boolean,
        val stitchPanX: Float, val stitchPanY: Float,
        val lockedLeftBoundaryX: Float?, val lockedRightBoundaryX: Float?,
        val lockedLeftBoundaryY: Float?, val lockedRightBoundaryY: Float?,
        val horizontal: Boolean, val edgePanAnchor: Float,
        val edgeRecoveryControl: ActiveControl?, val edgeRecoveryPanX: Float, val edgeRecoveryPanY: Float,
        val edgeRecoveryWidth: Int, val edgeRecoveryHeight: Int,
        val edgeRecoveryLeftBoundaryX: Float?, val edgeRecoveryRightBoundaryX: Float?,
        val edgeRecoveryLeftBoundaryY: Float?, val edgeRecoveryRightBoundaryY: Float?,
        val seamRecoveryIndex: Int?, val seamRecoveryPanX: Float, val seamRecoveryPanY: Float,
        val seamRecoveryWidth: Int, val seamRecoveryHeight: Int,
        val seamRecoveryLeftBoundaryX: Float?, val seamRecoveryRightBoundaryX: Float?,
        val seamRecoveryLeftBoundaryY: Float?, val seamRecoveryRightBoundaryY: Float?,
        val cropTop: Float, val cropBottom: Float, val cropMode: Boolean
    )
    private val photos = mutableListOf<Photo>()
    private val galleryPhotos = mutableListOf<GalleryPhoto>()
    private val galleryAlbums = mutableListOf<GalleryAlbum>()
    private val selectedGalleryUris = mutableListOf<Uri>()
    private val galleryExecutor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var galleryAlbumFilter: String? = null
    private var galleryLoadToken = 0
    private var pendingGalleryAfterPermission = false
    private var galleryPickerOpen = false
    private var pickerFromEditor = false
    private lateinit var galleryGrid: GridView
    private lateinit var galleryEmptyState: TextView
    private lateinit var galleryAlbumButton: TextView
    private lateinit var galleryCountLabel: TextView
    private lateinit var gallerySelectionLabel: TextView
    private lateinit var gallerySelectedStrip: LinearLayout
    private lateinit var galleryContinueButton: TextView
    private lateinit var galleryAdapter: BaseAdapter
    private var galleryCellSize = 0
    private var initialPhotoOrder: List<Photo> = emptyList()
    private var initialHorizontal = true
    private val controlLocks = StitchControlLocks()
    private val seamLocks: MutableList<Boolean> get() = controlLocks.seamLocks
    private val undoStack = mutableListOf<EditSnapshot>()
    private var leftEdgeLocked: Boolean
        get() = controlLocks.leftLocked
        set(value) { controlLocks.leftLocked = value }
    private var rightEdgeLocked: Boolean
        get() = controlLocks.rightLocked
        set(value) { controlLocks.rightLocked = value }
    private var stitchPanX = 0f
    private var stitchPanY = 0f
    /** Pan position at which the current outer-edge adjustment was unlocked. */
    private var edgePanAnchor = 0f
    /** Pre-commit state retained so unlocking the same edge can restore its hidden area. */
    private var edgeRecoveryControl: ActiveControl? = null
    private var edgeRecoveryPanX = 0f
    private var edgeRecoveryPanY = 0f
    private var edgeRecoveryWidth = 0
    private var edgeRecoveryHeight = 0
    private var edgeRecoveryLeftBoundaryX: Float? = null
    private var edgeRecoveryRightBoundaryX: Float? = null
    private var edgeRecoveryLeftBoundaryY: Float? = null
    private var edgeRecoveryRightBoundaryY: Float? = null
    /** Pre-commit frame and crop limits retained for re-editing a locked seam. */
    private var seamRecoveryIndex: Int? = null
    private var seamRecoveryPanX = 0f
    private var seamRecoveryPanY = 0f
    private var seamRecoveryWidth = 0
    private var seamRecoveryHeight = 0
    private var seamRecoveryLeftBoundaryX: Float? = null
    private var seamRecoveryRightBoundaryX: Float? = null
    private var seamRecoveryLeftBoundaryY: Float? = null
    private var seamRecoveryRightBoundaryY: Float? = null
    private var lockedLeftBoundaryX: Float? = null
    private var lockedRightBoundaryX: Float? = null
    private var lockedLeftBoundaryY: Float? = null
    private var lockedRightBoundaryY: Float? = null
    private var appendNext = false
    private var horizontal = true
    private var cropMode = false
    private var cropTop = 0f
    private var cropBottom = 0f
    private var pendingEdgeTapToLock: ActiveControl? = null
    private lateinit var root: LinearLayout
    private lateinit var preview: StitchPreview
    private lateinit var status: TextView
    private lateinit var undoAction: View
    private lateinit var resetAction: View
    private var aboutDialog: AlertDialog? = null

    private fun configureSystemBars(content: View) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                run { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom }
            }
            view.setPadding(baseLeft, baseTop + top, baseRight, baseBottom + bottom)
            insets
        }
        content.requestApplyInsets()
    }

    private fun captureSnapshot() = EditSnapshot(
        photos.map { PhotoState(it, it.visible, it.shift) }, seamLocks.toList(),
        leftEdgeLocked, rightEdgeLocked, stitchPanX, stitchPanY,
        lockedLeftBoundaryX, lockedRightBoundaryX, lockedLeftBoundaryY, lockedRightBoundaryY, horizontal,
        edgePanAnchor, edgeRecoveryControl, edgeRecoveryPanX, edgeRecoveryPanY,
        edgeRecoveryWidth, edgeRecoveryHeight,
        edgeRecoveryLeftBoundaryX, edgeRecoveryRightBoundaryX, edgeRecoveryLeftBoundaryY, edgeRecoveryRightBoundaryY,
        seamRecoveryIndex, seamRecoveryPanX, seamRecoveryPanY, seamRecoveryWidth, seamRecoveryHeight,
        seamRecoveryLeftBoundaryX, seamRecoveryRightBoundaryX, seamRecoveryLeftBoundaryY, seamRecoveryRightBoundaryY,
        cropTop, cropBottom, cropMode
    )

    private fun recordUndo(snapshot: EditSnapshot) {
        undoStack.add(snapshot)
        if (undoStack.size > 50) undoStack.removeAt(0)
        updateUndoAction()
    }

    private fun saveUndoPoint() = recordUndo(captureSnapshot())

    private fun clearLockedBoundaries() {
        lockedLeftBoundaryX=null; lockedRightBoundaryX=null
        lockedLeftBoundaryY=null; lockedRightBoundaryY=null
    }

    private fun clearSeamRecovery() {
        seamRecoveryIndex=null
        seamRecoveryPanX=0f; seamRecoveryPanY=0f
        seamRecoveryWidth=0; seamRecoveryHeight=0
        seamRecoveryLeftBoundaryX=null; seamRecoveryRightBoundaryX=null
        seamRecoveryLeftBoundaryY=null; seamRecoveryRightBoundaryY=null
    }

    private fun clearEdgeRecovery() {
        edgeRecoveryControl=null
        edgeRecoveryPanX=0f; edgeRecoveryPanY=0f
        edgeRecoveryWidth=0; edgeRecoveryHeight=0
        edgeRecoveryLeftBoundaryX=null; edgeRecoveryRightBoundaryX=null
        edgeRecoveryLeftBoundaryY=null; edgeRecoveryRightBoundaryY=null
    }

    private fun captureLockedFrame() {
        if (!::preview.isInitialized) return
        if (horizontal) {
            lockedLeftBoundaryX = -stitchPanX
            val frameWidth=(preview.layoutParams as? FrameLayout.LayoutParams)?.width?.takeIf { it>0 } ?: preview.width
            lockedRightBoundaryX = frameWidth - stitchPanX
        } else {
            lockedLeftBoundaryY = -stitchPanY
            val frameHeight=(preview.layoutParams as? FrameLayout.LayoutParams)?.height?.takeIf { it>0 } ?: preview.height
            lockedRightBoundaryY = frameHeight - stitchPanY
        }
    }

    private fun toggleEdgeControl(left: Boolean) {
        saveUndoPoint()
        clearSeamRecovery()
        val wasUnlocked = if (left) !leftEdgeLocked else !rightEdgeLocked
        val selectedControl = if (left) ActiveControl.LEFT_EDGE else ActiveControl.RIGHT_EDGE
        if (wasUnlocked) {
            edgeRecoveryControl = selectedControl
            edgeRecoveryPanX = stitchPanX
            edgeRecoveryPanY = stitchPanY
            edgeRecoveryWidth = preview.expandedWidth
            edgeRecoveryHeight = preview.expandedHeight
            edgeRecoveryLeftBoundaryX = lockedLeftBoundaryX
            edgeRecoveryRightBoundaryX = lockedRightBoundaryX
            edgeRecoveryLeftBoundaryY = lockedLeftBoundaryY
            edgeRecoveryRightBoundaryY = lockedRightBoundaryY
            controlLocks.lockAll()
            preview.finalizeVisibleComposition()
            captureLockedFrame()
            edgePanAnchor = if(horizontal) stitchPanX else stitchPanY
        } else {
            if (left) controlLocks.toggleLeft() else controlLocks.toggleRight()
            val canRestorePreviousAdjustment = edgeRecoveryControl == selectedControl &&
                if(horizontal) edgeRecoveryWidth > 0 else edgeRecoveryHeight > 0
            if (canRestorePreviousAdjustment) {
                stitchPanX = edgeRecoveryPanX
                stitchPanY = edgeRecoveryPanY
                lockedLeftBoundaryX = edgeRecoveryLeftBoundaryX
                lockedRightBoundaryX = edgeRecoveryRightBoundaryX
                lockedLeftBoundaryY = edgeRecoveryLeftBoundaryY
                lockedRightBoundaryY = edgeRecoveryRightBoundaryY
                preview.restoreExpandedFrame(edgeRecoveryWidth, edgeRecoveryHeight)
            } else {
                clearEdgeRecovery()
                preview.restoreExpandedFrame()
            }
            edgePanAnchor = if(horizontal) stitchPanX else stitchPanY
        }
        preview.invalidate()
        updateUndoAction()
    }

    private fun toggleSeamControl(index: Int) {
        if (index !in seamLocks.indices) return
        saveUndoPoint()
        val wasUnlocked = !seamLocks[index]
        controlLocks.toggleSeam(index)
        clearEdgeRecovery()
        if (wasUnlocked) {
            seamRecoveryIndex = index
            seamRecoveryPanX = stitchPanX
            seamRecoveryPanY = stitchPanY
            seamRecoveryWidth = preview.expandedWidth
            seamRecoveryHeight = preview.expandedHeight
            seamRecoveryLeftBoundaryX = lockedLeftBoundaryX
            seamRecoveryRightBoundaryX = lockedRightBoundaryX
            seamRecoveryLeftBoundaryY = lockedLeftBoundaryY
            seamRecoveryRightBoundaryY = lockedRightBoundaryY
            preview.finalizeVisibleComposition()
            captureLockedFrame()
            edgePanAnchor = if(horizontal) stitchPanX else stitchPanY
        } else {
            if (seamRecoveryIndex == index && seamRecoveryWidth > 0 && seamRecoveryHeight > 0) {
                stitchPanX = seamRecoveryPanX
                stitchPanY = seamRecoveryPanY
                lockedLeftBoundaryX = seamRecoveryLeftBoundaryX
                lockedRightBoundaryX = seamRecoveryRightBoundaryX
                lockedLeftBoundaryY = seamRecoveryLeftBoundaryY
                lockedRightBoundaryY = seamRecoveryRightBoundaryY
                preview.restoreExpandedFrame(seamRecoveryWidth, seamRecoveryHeight)
            } else {
                clearSeamRecovery()
                preview.restoreExpandedFrame()
            }
            edgePanAnchor = if(horizontal) stitchPanX else stitchPanY
        }
        preview.invalidate()
        updateUndoAction()
    }

    private fun resetControlsLocked() {
        controlLocks.lockAll()
        controlLocks.ensureSeams((photos.count { it.visible } - 1).coerceAtLeast(0))
        controlLocks.lockAll()
        clearLockedBoundaries()
        edgePanAnchor = if(horizontal) stitchPanX else stitchPanY
        clearEdgeRecovery()
        clearSeamRecovery()
    }

    private fun updateUndoAction() {
        if (::undoAction.isInitialized) {
            undoAction.isEnabled = undoStack.isNotEmpty()
            undoAction.alpha = if (undoStack.isNotEmpty()) 1f else .35f
        }
    }

    private fun updateResetAction() {
        if (::resetAction.isInitialized) {
            val enabled = photos.isNotEmpty()
            resetAction.isEnabled = enabled
            resetAction.alpha = if (enabled) 1f else .35f
        }
    }

    private fun ensureInitialPhotoOrder() {
        if (initialPhotoOrder.isEmpty() && photos.isNotEmpty()) {
            initialPhotoOrder = photos.map { it.copy(visible = true, shift = 0f) }
            initialHorizontal = horizontal
        }
    }

    private fun resetEditor() {
        if (photos.isEmpty()) return
        ensureInitialPhotoOrder()
        saveUndoPoint()
        photos.clear()
        photos.addAll(initialPhotoOrder)
        photos.forEach { it.visible = true; it.shift = 0f }
        horizontal = initialHorizontal
        stitchPanX = 0f; stitchPanY = 0f
        cropTop = 0f; cropBottom = 0f
        cropMode = false
        seamLocks.clear()
        resetControlsLocked()
        showEditor()
        toast("已恢复初始状态")
    }

    private fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        photos.clear()
        snapshot.photos.forEach { state ->
            state.photo.visible = state.visible
            state.photo.shift = state.shift
            photos.add(state.photo)
        }
        seamLocks.clear(); seamLocks.addAll(snapshot.seamLocks)
        leftEdgeLocked = snapshot.leftEdgeLocked; rightEdgeLocked = snapshot.rightEdgeLocked
        stitchPanX = snapshot.stitchPanX; stitchPanY = snapshot.stitchPanY
        edgePanAnchor = snapshot.edgePanAnchor
        edgeRecoveryControl = snapshot.edgeRecoveryControl
        edgeRecoveryPanX = snapshot.edgeRecoveryPanX
        edgeRecoveryPanY = snapshot.edgeRecoveryPanY
        edgeRecoveryWidth = snapshot.edgeRecoveryWidth
        edgeRecoveryHeight = snapshot.edgeRecoveryHeight
        edgeRecoveryLeftBoundaryX = snapshot.edgeRecoveryLeftBoundaryX
        edgeRecoveryRightBoundaryX = snapshot.edgeRecoveryRightBoundaryX
        edgeRecoveryLeftBoundaryY = snapshot.edgeRecoveryLeftBoundaryY
        edgeRecoveryRightBoundaryY = snapshot.edgeRecoveryRightBoundaryY
        seamRecoveryIndex = snapshot.seamRecoveryIndex
        seamRecoveryPanX = snapshot.seamRecoveryPanX
        seamRecoveryPanY = snapshot.seamRecoveryPanY
        seamRecoveryWidth = snapshot.seamRecoveryWidth
        seamRecoveryHeight = snapshot.seamRecoveryHeight
        seamRecoveryLeftBoundaryX = snapshot.seamRecoveryLeftBoundaryX
        seamRecoveryRightBoundaryX = snapshot.seamRecoveryRightBoundaryX
        seamRecoveryLeftBoundaryY = snapshot.seamRecoveryLeftBoundaryY
        seamRecoveryRightBoundaryY = snapshot.seamRecoveryRightBoundaryY
        lockedLeftBoundaryX = snapshot.lockedLeftBoundaryX; lockedRightBoundaryX = snapshot.lockedRightBoundaryX
        lockedLeftBoundaryY = snapshot.lockedLeftBoundaryY; lockedRightBoundaryY = snapshot.lockedRightBoundaryY
        horizontal = snapshot.horizontal
        cropTop = snapshot.cropTop; cropBottom = snapshot.cropBottom
        cropMode = snapshot.cropMode
        showEditor()
        toast("已撤销")
    }

    private fun requiredGalleryPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasGalleryAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestGalleryPermission(openPickerAfter: Boolean) {
        pendingGalleryAfterPermission = openPickerAfter
        if (hasGalleryAccess()) {
            if (openPickerAfter) openGalleryPicker(appendNext) else showHome()
            return
        }
        requestPermissions(requiredGalleryPermissions(), 2001)
    }

    private fun openGalleryPicker(append: Boolean) {
        appendNext = append
        pickerFromEditor = photos.isNotEmpty()
        if (!hasGalleryAccess()) {
            requestGalleryPermission(openPickerAfter = true)
            return
        }
        galleryAlbumFilter = null
        selectedGalleryUris.clear()
        galleryPickerOpen = true
        showGalleryPicker()
        loadGalleryPhotos()
    }

    private fun loadGalleryPhotos() {
        val token = ++galleryLoadToken
        galleryPhotos.clear()
        galleryAlbums.clear()
        galleryAlbums.add(GalleryAlbum(null, "所有照片", 0, null))
        refreshGalleryPicker()
        galleryEmptyState.text = "正在读取相册…"
        galleryExecutor.execute {
            val result = mutableListOf<GalleryPhoto>()
            try {
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )
                contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                    val bucketNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val bucketId = if (bucketIdColumn >= 0) cursor.getString(bucketIdColumn).orEmpty() else ""
                        val bucketName = if (bucketNameColumn >= 0) cursor.getString(bucketNameColumn).orEmpty().ifBlank { "其他相册" } else "其他相册"
                        val date = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                        result.add(GalleryPhoto(ContentUris.withAppendedId(collection, id), bucketId, bucketName, date))
                    }
                }
            } catch (_: SecurityException) { }
            mainHandler.post {
                if (!galleryPickerOpen || token != galleryLoadToken) return@post
                galleryPhotos.clear()
                galleryPhotos.addAll(result)
                val albums = result.groupBy { it.bucketId }.map { (id, items) ->
                    GalleryAlbum(id, items.first().bucketName, items.size, items.firstOrNull()?.uri)
                }.sortedBy { it.name.lowercase() }
                galleryAlbums.clear()
                galleryAlbums.add(GalleryAlbum(null, "所有照片", result.size, result.firstOrNull()?.uri))
                galleryAlbums.addAll(albums)
                updateGalleryAlbumLabel()
                refreshGalleryPicker()
            }
        }
    }

    private fun filteredGalleryPhotos(): List<GalleryPhoto> = if (galleryAlbumFilter == null) galleryPhotos.toList()
        else galleryPhotos.filter { it.bucketId == galleryAlbumFilter }

    private fun showGalleryPicker() {
        galleryPickerOpen = true
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Keep the system gesture area visually continuous with the white bottom action dock.
            setBackgroundColor(Color.WHITE)
        }
        root = page
        setContentView(page)
        configureSystemBars(page)

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(Color.WHITE)
        }
        val back = FrameLayout(this).apply {
            background = rounded(0xFFF3F4F7.toInt(), 12)
            contentDescription = "返回"
            isClickable = true
            setOnClickListener { closeGalleryPicker() }
            addView(iconImage(R.drawable.ic_back, 0xFF303541.toInt()), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        header.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("选择照片", 18, 0xFF20232A.toInt(), true))
        heading.addView(label("最多 12 张 · 按选择顺序拼接", 12, 0xFF858A96.toInt()).apply { setPadding(0, dp(2), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(12) })
        header.addView(TextView(this).apply {
            text = "清空"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF727987.toInt())
            background = rounded(0xFFF3F4F7.toInt(), 11)
            setOnClickListener { selectedGalleryUris.clear(); refreshGalleryPicker() }
        }, LinearLayout.LayoutParams(dp(54), dp(36)))
        page.addView(header)

        val albumRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        galleryAlbumButton = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF252936.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFFE5E7EC.toInt())
            }
            setOnClickListener { showAlbumChooser() }
        }
        albumRow.addView(galleryAlbumButton, LinearLayout.LayoutParams(-2, dp(40)))
        albumRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        galleryCountLabel = label("", 12, 0xFF777E8B.toInt(), true)
        albumRow.addView(galleryCountLabel)
        page.addView(albumRow)

        val gridFrame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        galleryGrid = GridView(this).apply {
            numColumns = 3
            columnWidth = dp(110)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(1)
            verticalSpacing = dp(1)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            setSelector(android.R.color.transparent)
            setBackgroundColor(Color.WHITE)
            clipToPadding = false
        }
        galleryEmptyState = label("", 14, 0xFF7B818D.toInt()).apply { gravity = Gravity.CENTER }
        gridFrame.addView(galleryGrid, FrameLayout.LayoutParams(-1, -1))
        gridFrame.addView(galleryEmptyState, FrameLayout.LayoutParams(-1, -1))
        page.addView(gridFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        val selectedHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        selectedHeader.addView(label("已选照片", 14, 0xFF303541.toInt(), true))
        selectedHeader.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        gallerySelectionLabel = label("0 / 12", 13, 0xFFEC9132.toInt(), true)
        selectedHeader.addView(gallerySelectionLabel)
        selectedHeader.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        selectedHeader.addView(label("点缩略图移除", 12, 0xFF858A96.toInt()))
        bottom.addView(selectedHeader)
        val selectedScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        gallerySelectedStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        selectedScroll.addView(gallerySelectedStrip)
        bottom.addView(selectedScroll, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(8) })
        galleryContinueButton = button("选择至少 2 张照片", true) { confirmGallerySelection() }.apply { isEnabled = false; alpha = .5f }
        bottom.addView(galleryContinueButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        page.addView(bottom)

        galleryAdapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredGalleryPhotos().size
            override fun getItem(position: Int): Any = filteredGalleryPhotos()[position]
            override fun getItemId(position: Int): Long = filteredGalleryPhotos()[position].uri.lastPathSegment?.toLongOrNull() ?: position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = filteredGalleryPhotos()[position]
                val cell: FrameLayout
                val holder: GalleryHolder
                if (convertView == null) {
                    cell = FrameLayout(this@MainActivity).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                    }
                    val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    cell.addView(image, FrameLayout.LayoutParams(-1, -1))
                    val overlay = View(this@MainActivity).apply { setBackgroundColor(0x330C1020) }
                    cell.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                    val badge = TextView(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    }
                    cell.addView(badge, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                    holder = GalleryHolder(image, overlay, badge)
                    cell.tag = holder
                } else {
                    cell = convertView as FrameLayout
                    holder = cell.tag as GalleryHolder
                }
                val selectedIndex = selectedGalleryUris.indexOf(item.uri)
                holder.overlay.visibility = if (selectedIndex >= 0) View.VISIBLE else View.GONE
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = if (selectedIndex >= 0) (selectedIndex + 1).toString() else ""
                holder.badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (selectedIndex >= 0) 0xFF7158D8.toInt() else 0x550C1020)
                    setStroke(dp(1), Color.WHITE)
                }
                cell.contentDescription = if (selectedIndex >= 0) "已选择第 ${selectedIndex + 1} 张，点击取消选择" else "选择照片"
                if (holder.image.tag != item.uri) {
                    holder.image.tag = item.uri
                    holder.image.setImageDrawable(null)
                    galleryExecutor.execute {
                        val bitmap = try { contentResolver.loadThumbnail(item.uri, Size(dp(240), dp(240)), CancellationSignal()) } catch (_: Exception) { null }
                        if (bitmap != null) mainHandler.post { if (holder.image.tag == item.uri) holder.image.setImageBitmap(bitmap) }
                    }
                }
                cell.layoutParams = AbsListView.LayoutParams(-1, galleryCellSize.takeIf { it > 0 } ?: dp(88))
                // Let GridView own the tap for the whole tile. Click listeners on recycled
                // child layers can otherwise make touch handling inconsistent between cells.
                cell.isClickable = false
                cell.isFocusable = false
                holder.image.isClickable = false
                holder.overlay.isClickable = false
                holder.badge.isClickable = false
                return cell
            }
        }
        galleryGrid.adapter = galleryAdapter
        galleryGrid.setOnItemClickListener { _, _, position, _ ->
            filteredGalleryPhotos().getOrNull(position)?.let { toggleGalleryPhoto(it.uri) }
        }
        refreshGalleryPicker()
        galleryGrid.post {
            val columns = galleryGrid.numColumns.coerceAtLeast(1)
            val availableWidth = galleryGrid.width - galleryGrid.paddingLeft - galleryGrid.paddingRight - galleryGrid.horizontalSpacing * (columns - 1)
            val squareSize = (availableWidth / columns).coerceAtLeast(1)
            if (galleryCellSize != squareSize) {
                galleryCellSize = squareSize
                galleryAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateGalleryAlbumLabel() {
        if (::galleryAlbumButton.isInitialized) {
            val album = galleryAlbums.firstOrNull { it.id == galleryAlbumFilter }
            galleryAlbumButton.text = "${album?.name ?: "所有照片"}  ▾"
        }
    }

    private fun showAlbumChooser() {
        val names = galleryAlbums.map { "${it.name}  ·  ${it.count}" }.toTypedArray()
        val selected = galleryAlbums.indexOfFirst { it.id == galleryAlbumFilter }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择相册")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                galleryAlbumFilter = galleryAlbums.getOrNull(which)?.id
                updateGalleryAlbumLabel()
                refreshGalleryPicker()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleGalleryPhoto(uri: Uri) {
        if (selectedGalleryUris.remove(uri)) {
            refreshGalleryPicker()
            return
        }
        val limit = if (appendNext) (12 - photos.size).coerceAtLeast(0) else 12
        if (selectedGalleryUris.size >= limit) {
            toast(if (limit == 0) "最多拼接 12 张照片" else "最多再添加 ${limit} 张照片")
            return
        }
        selectedGalleryUris.add(uri)
        refreshGalleryPicker()
    }

    private fun refreshGalleryPicker() {
        if (!::galleryGrid.isInitialized) return
        if (::galleryAdapter.isInitialized) galleryAdapter.notifyDataSetChanged()
        val visibleCount = filteredGalleryPhotos().size
        if (::galleryCountLabel.isInitialized) galleryCountLabel.text = "$visibleCount 张照片"
        if (::galleryEmptyState.isInitialized) {
            galleryEmptyState.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
            galleryEmptyState.text = if (galleryPhotos.isEmpty()) "没有找到可访问的照片" else "这个相册里还没有照片"
        }
        if (::gallerySelectedStrip.isInitialized) {
            val selectionLimit = if (appendNext) (12 - photos.size).coerceAtLeast(0) else 12
            gallerySelectionLabel.text = "${selectedGalleryUris.size} / $selectionLimit"
            gallerySelectedStrip.removeAllViews()
            if (selectedGalleryUris.isEmpty()) {
                gallerySelectedStrip.addView(label("选择的照片会按顺序排列在这里", 12, 0xFF9A9FAB.toInt()))
            }
            selectedGalleryUris.forEachIndexed { index, uri ->
                val tile = FrameLayout(this).apply {
                    background = rounded(0xFFF0F1F5.toInt(), 8)
                    setOnClickListener { selectedGalleryUris.remove(uri); refreshGalleryPicker() }
                }
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageURI(uri) }
                tile.addView(image, FrameLayout.LayoutParams(dp(52), dp(52)))
                tile.addView(TextView(this).apply {
                    text = (index + 1).toString(); gravity = Gravity.CENTER; textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = rounded(0xFFFF9F43.toInt(), 18)
                }, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(2), dp(2), 0) })
                gallerySelectedStrip.addView(tile, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(7) })
            }
        }
        if (::galleryContinueButton.isInitialized) {
            val needed = if (appendNext) 1 else 2
            val count = selectedGalleryUris.size
            val ready = count >= needed
            galleryContinueButton.isEnabled = ready
            galleryContinueButton.alpha = if (ready) 1f else .48f
            galleryContinueButton.text = when {
                appendNext && count == 0 -> "选择照片后添加到拼接"
                appendNext -> "添加 $count 张照片 · 继续"
                count == 0 -> "选择至少 2 张照片"
                count < 2 -> "还需选择 ${2 - count} 张照片"
                else -> "开始拼接 · $count 张"
            }
        }
    }

    private fun confirmGallerySelection() {
        val selected = selectedGalleryUris.toList()
        val appending = appendNext
        if (selected.size < if (appending) 1 else 2) return
        val oldPhotoCount = photos.size
        if (!appending) {
            photos.clear()
            initialPhotoOrder = emptyList()
            initialHorizontal = horizontal
            seamLocks.clear()
            undoStack.clear()
            cropTop = 0f; cropBottom = 0f
            controlLocks.lockAll()
            stitchPanX = 0f; stitchPanY = 0f
            clearLockedBoundaries()
        }
        val previousInitial = if (appending) initialPhotoOrder else emptyList()
        val existingUris = photos.map { it.uri }.toSet()
        var unreadableCount = 0
        selected.filterNot { it in existingUris }.forEach { uri ->
            try {
                val bitmap = decodePhoto(uri)
                if (bitmap == null) unreadableCount++ else photos.add(Photo(uri, bitmap))
            } catch (_: Exception) { unreadableCount++ }
        }
        if (photos.size < 2) {
            photos.clear()
            initialPhotoOrder = emptyList()
            if (unreadableCount > 0) {
                toast("有 $unreadableCount 张照片无法读取，请换选可打开的本地图片")
            } else {
                toast("至少选择 2 张图片才能开始拼接")
                closeGalleryPicker()
                showHome()
            }
            return
        }
        val addedPhotos = photos.drop(oldPhotoCount)
        initialPhotoOrder = previousInitial + addedPhotos
        appendNext = false
        galleryPickerOpen = false
        resetControlsLocked()
        showEditor()
        if (unreadableCount > 0) toast("已跳过 $unreadableCount 张无法读取的照片")
    }

    private fun closeGalleryPicker() {
        galleryPickerOpen = false
        galleryLoadToken++
        appendNext = false
        if (pickerFromEditor && photos.isNotEmpty()) showEditor() else showHome()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 2001) return
        if (hasGalleryAccess()) {
            getSharedPreferences("gallery_access", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
            val shouldOpenPicker = pendingGalleryAfterPermission
            pendingGalleryAfterPermission = false
            if (shouldOpenPicker) openGalleryPicker(appendNext) else showHome()
        } else {
            pendingGalleryAfterPermission = false
            if (pickerFromEditor && photos.isNotEmpty()) showEditor() else showHome()
            toast("需要允许访问照片，才能在应用内浏览相册")
        }
    }

    override fun onBackPressed() {
        if (galleryPickerOpen) closeGalleryPicker()
        else if (photos.isNotEmpty()) showHome()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        galleryLoadToken++
        galleryExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordAppVisit()
        val prefs = getSharedPreferences("gallery_access", MODE_PRIVATE)
        if (hasGalleryAccess()) {
            openGalleryPicker(append = false)
        } else {
            showHome()
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                requestGalleryPermission(openPickerAfter = true)
            }
        }
    }

    private fun base(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(18)); setBackgroundColor(Color.rgb(248,247,251)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(outer) }
        setContentView(scroll); configureSystemBars(scroll); root = outer
        return outer
    }

    private fun showHome() {
        val r = base()
        r.gravity = Gravity.CENTER
        r.setPadding(dp(24), dp(24), dp(24), dp(24))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val iconTile = FrameLayout(this).apply {
            background = rounded(0xFFF0EAFE.toInt(), 25)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        }
        content.addView(iconTile, LinearLayout.LayoutParams(dp(96), dp(96)))
        content.addView(label("liKePic拼图工坊", 23, 0xFF242833.toInt(), true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        content.addView(label("把多张照片，自然拼成一张长图", 14, 0xFF858B97.toInt()).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

        val permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 18)
        }
        val galleryIcon = FrameLayout(this).apply {
            background = rounded(0xFFF2EEFF.toInt(), 13)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_gallery)
                setColorFilter(0xFF7158D8.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        }
        permissionCard.addView(galleryIcon, LinearLayout.LayoutParams(dp(46), dp(46)))
        val permissionCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permissionCopy.addView(label("需要访问照片", 15, 0xFF303541.toInt(), true))
        permissionCopy.addView(label("授权后即可浏览并选择要拼接的图片", 12, 0xFF858A96.toInt()).apply {
            setPadding(0, dp(5), 0, 0)
        })
        permissionCard.addView(permissionCopy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
        content.addView(permissionCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })

        content.addView(button("授予权限并选择照片", true) {
            requestGalleryPermission(openPickerAfter = true)
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(16) })
        content.addView(label("至少选择 2 张 · 最多 12 张", 12, 0xFF858B97.toInt()).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        r.addView(content, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showAbout() {
        if (aboutDialog?.isShowing == true) return
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersionName = packageInfo.versionName ?: "1.0.0"
        val currentVersionCode = packageInfo.longVersionCode
        lateinit var dialog: AlertDialog

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(Color.WHITE, 22)
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brandName = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        brandName.addView(label("liKePic", 21, 0xFF242833.toInt(), true))
        brandName.addView(label("图片拼接与裁剪", 13, 0xFF858A96.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        brand.addView(brandName, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        val closeAction = FrameLayout(this).apply {
            background = rounded(0xFFF0F1F5.toInt(), 12)
            contentDescription = "关闭关于"
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            addView(label("×", 27, 0xFF626A78.toInt(), true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        brand.addView(closeAction, LinearLayout.LayoutParams(dp(36), dp(36)))
        panel.addView(brand)

        panel.addView(View(this).apply { setBackgroundColor(0xFFECEEF2.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(18); bottomMargin = dp(8)
        })
        fun addInfoRow(title: String, value: String, action: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(38) }
            row.addView(label(title, 13, 0xFF858A96.toInt()), LinearLayout.LayoutParams(dp(72), -2))
            val valueView = label(value, 14, if (action == null) 0xFF303541.toInt() else 0xFF7056D8.toInt(), action == null)
            if (action != null) {
                valueView.paintFlags = valueView.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                valueView.setOnClickListener { action() }
            }
            row.addView(valueView, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(row)
        }
        addInfoRow("作者", "liKeYunKeji")
        addInfoRow("GitHub", "github.com/likeyun") { openExternalLink("https://github.com/likeyun") }

        val versionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(0xFFF6F4FB.toInt(), 16)
        }
        versionCard.addView(label("版本信息", 14, 0xFF303541.toInt(), true))
        versionCard.addView(label("当前版本  $currentVersionName  ($currentVersionCode)", 13, 0xFF626A78.toInt()).apply {
            setPadding(0, dp(7), 0, dp(9))
        })
        val versionState = label("正在检查更新…", 13, 0xFF7056D8.toInt(), true)
        versionCard.addView(versionState)
        val versionDetails = label("", 12, 0xFF858A96.toInt()).apply { setPadding(0, dp(4), 0, 0) }
        versionCard.addView(versionDetails)
        val updateButton = button("检查更新", true) { }
        versionCard.addView(updateButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        panel.addView(versionCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        var latestVersion: RemoteAppVersion? = null
        fun openDownloadPage(remote: RemoteAppVersion) {
            if (!remote.downloadUrl.startsWith("https://") && !remote.downloadUrl.startsWith("http://")) {
                toast("下载地址无效")
                return
            }
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(remote.downloadUrl))) }
            catch (_: Exception) { toast("无法打开下载页面") }
        }
        val runVersionCheck: () -> Unit = {
            updateButton.isEnabled = false
            updateButton.alpha = .62f
            updateButton.text = "检查中…"
            versionState.text = "正在检查更新…"
            versionState.setTextColor(0xFF7056D8.toInt())
            versionDetails.text = ""
            galleryExecutor.execute {
                val result = runCatching { fetchRemoteAppVersion() }
                mainHandler.post {
                    if (aboutDialog !== dialog || !dialog.isShowing) return@post
                    result.onSuccess { remote ->
                        latestVersion = remote
                        val hasUpdate = remote.versionCode > currentVersionCode
                        if (hasUpdate) {
                            versionState.text = if (remote.forceUpdate) "发现新版本 · 需要更新" else "发现新版本"
                            versionState.setTextColor(0xFFEC9132.toInt())
                            versionDetails.text = buildString {
                                append(remote.name.ifBlank { "V${remote.version}" })
                                if (remote.description.isNotBlank()) append("\n${remote.description}")
                                if (remote.publishTime.isNotBlank()) append("\n发布时间：${remote.publishTime}")
                            }
                            updateButton.text = if (remote.forceUpdate) "立即更新" else "下载新版本"
                            if (remote.forceUpdate) {
                                dialog.setCancelable(false)
                                dialog.setCanceledOnTouchOutside(false)
                                closeAction.visibility = View.GONE
                            }
                        } else {
                            versionState.text = "当前已是最新版本"
                            versionState.setTextColor(0xFF39836C.toInt())
                            versionDetails.text = "${remote.name.ifBlank { "V${remote.version}" }} · ${remote.publishTime}"
                            updateButton.text = "重新检查"
                        }
                        updateButton.isEnabled = true
                        updateButton.alpha = 1f
                    }.onFailure { error ->
                        latestVersion = null
                        versionState.text = "检查失败"
                        versionState.setTextColor(0xFFB64C4C.toInt())
                        versionDetails.text = "${error.message?.takeIf { it.isNotBlank() } ?: "网络暂不可用"}，请稍后重试"
                        updateButton.text = "重试"
                        updateButton.isEnabled = true
                        updateButton.alpha = 1f
                    }
                }
            }
        }
        updateButton.setOnClickListener {
            val remote = latestVersion
            if (remote != null && remote.versionCode > currentVersionCode) openDownloadPage(remote)
            else runVersionCheck()
        }

        dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        aboutDialog = dialog
        dialog.setOnDismissListener { if (aboutDialog === dialog) aboutDialog = null }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(36)).coerceAtMost(dp(440)), -2)
        runVersionCheck()
    }

    private fun fetchRemoteAppVersion(): RemoteAppVersion {
        val connection = (URL("https://keji.likeyuns.cn/likePic").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "liKePic-Android")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw IllegalStateException("服务器响应 $responseCode")
            val response = org.json.JSONObject(body)
            if (response.optInt("code") != 200) throw IllegalStateException(response.optString("msg", "版本检查失败"))
            val data = response.optJSONObject("data") ?: throw IllegalStateException("版本信息格式无效")
            if (data.optInt("status", 0) != 1) throw IllegalStateException("版本信息暂不可用")
            val versionCode = data.optLong("version_code", -1L)
            val version = data.optString("version").trim()
            if (versionCode < 0 || version.isBlank()) throw IllegalStateException("版本信息不完整")
            return RemoteAppVersion(
                version = version,
                versionCode = versionCode,
                name = data.optString("name").trim(),
                description = data.optString("description").trim(),
                downloadUrl = data.optString("download_url").trim(),
                forceUpdate = data.optBoolean("force_update", false),
                publishTime = data.optString("publish_time").trim()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openExternalLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("无法打开链接") }
    }

    private fun showEditor() {
        ensureInitialPhotoOrder()
        val r=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root=r
        setContentView(r)
        configureSystemBars(r)

        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(7),dp(16),dp(7)); setBackgroundColor(Color.WHITE) }
        val homeAction=FrameLayout(this).apply {
            background=rounded(0xFFF0F1F5.toInt(),12)
            contentDescription="返回首页"
            isClickable=true
            setOnClickListener { openGalleryPicker(append = false) }
            addView(iconImage(R.drawable.ic_back, 0xFF343947.toInt()),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        }
        top.addView(homeAction,LinearLayout.LayoutParams(dp(36),dp(38)))
        top.addView(label(if(cropMode) "裁剪" else "liKePic拼图工坊",16,0xFF292D37.toInt(),true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,dp(40),1f))
        undoAction=FrameLayout(this).apply {
            background=rounded(0xFFF0F1F5.toInt(),12)
            contentDescription="撤回上一步"
            isClickable=true
            setOnClickListener { undo() }
            addView(iconImage(R.drawable.ic_undo, 0xFF283142.toInt()),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        }
        top.addView(undoAction,LinearLayout.LayoutParams(dp(36),dp(38)).apply { marginEnd=dp(6) })
        updateUndoAction()
        resetAction=FrameLayout(this).apply {
            background=rounded(0xFFFFF2E4.toInt(),12)
            contentDescription="恢复初始状态"
            isClickable=true
            setOnClickListener { resetEditor() }
            addView(iconImage(R.drawable.ic_reset, 0xFFE98A31.toInt()),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        }
        top.addView(resetAction,LinearLayout.LayoutParams(dp(36),dp(38)).apply { marginEnd=dp(6) })
        updateResetAction()
        val aboutAction=FrameLayout(this).apply {
            background=rounded(0xFFF0F1F5.toInt(),12)
            contentDescription="关于"
            isClickable=true
            setOnClickListener { showAbout() }
            addView(iconImage(R.drawable.ic_about, 0xFF343947.toInt()),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        }
        top.addView(aboutAction,LinearLayout.LayoutParams(dp(36),dp(38)))
        r.addView(top)

        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFFE8EAF0.toInt())
            }
        }
        hint.addView(View(this).apply { background = rounded(0xFFED9A38.toInt(), 2) }, LinearLayout.LayoutParams(dp(3), dp(34)))
        val hintCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        hintCopy.addView(label(if (cropMode) "拖动橙色边界调整裁剪" else "拖动图片调整拼接", 14, 0xFF292D37.toInt(), true))
        hintCopy.addView(label(if (cropMode) "调整最终图片的保留范围" else "微调画面边界 · 点击控制点锁定", 12, 0xFF737A87.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        hint.addView(hintCopy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        r.addView(hint, LinearLayout.LayoutParams(-1, dp(62)).apply { setMargins(dp(16), dp(9), dp(16), dp(5)) })
        val middle=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xFFF2F3F6.toInt()) }
        r.addView(middle,LinearLayout.LayoutParams(-1,0,1f))
        val stage=FrameLayout(this).apply { setBackgroundColor(0xFFF2F3F6.toInt()) }
        middle.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        preview=StitchPreview(this,photos,horizontal)
        preview.cropMode=cropMode
        stage.addView(preview,FrameLayout.LayoutParams(-1,dp(320),Gravity.CENTER))
        stage.post {
            if(preview.parent===stage) {
                val shown=photos.filter { it.visible }
                val combinedAspect=if(horizontal) shown.sumOf { it.bitmap.width.toDouble()/it.bitmap.height } else 1.0/shown.sumOf { it.bitmap.height.toDouble()/it.bitmap.width }.coerceAtLeast(.01)
                // Fit the complete stitched canvas inside the stage while preserving
                // its aspect ratio. A forced minimum width made long vertical strips
                // taller than the stage and pushed their top/bottom controls off-screen.
                val targetWidth=minOf(stage.width.toDouble(),stage.height*combinedAspect).toInt().coerceAtLeast(1)
                val targetHeight=(targetWidth/combinedAspect).toInt().coerceAtLeast(1)
                preview.setExpandedFrame(targetWidth,targetHeight)
                preview.post { if(preview.parent===stage) preview.updateExtent(shown) }
            }
        }
        status=label("",11,0xFF737A87.toInt()).apply { gravity=Gravity.CENTER; setPadding(0,dp(2),0,dp(2)) }
        r.addView(status,LinearLayout.LayoutParams(-1,dp(22)))

        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(8))
            elevation = dp(10).toFloat()
        }
        val separator = View(this).apply { setBackgroundColor(0xFFE8EAF0.toInt()) }
        r.addView(separator, LinearLayout.LayoutParams(-1, dp(1)))
        val toolTitles = listOf(if(horizontal) "竖向" else "横向", if(cropMode) "完成裁剪" else "裁剪", "保存")
        toolTitles.forEachIndexed { index, title ->
            val isCropSelected = index == 1 && cropMode
            val isSave = index == 2
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(5), dp(3), dp(4))
                background = when {
                    isSave -> rounded(0xFF7158D8.toInt(), 14)
                    isCropSelected -> rounded(0xFFF1EDFF.toInt(), 14)
                    else -> rounded(Color.TRANSPARENT, 14)
                }
                isClickable = true
                contentDescription = when(index) { 0 -> "切换为${if(horizontal) "竖向" else "横向"}拼接"; 1 -> title; else -> "保存拼接图片" }
                setOnClickListener {
                    when(index) {
                        0 -> {
                            saveUndoPoint()
                            horizontal = !horizontal
                            cropMode = false
                            photos.forEach { it.shift = 0f }
                            seamLocks.clear()
                            resetControlsLocked()
                            stitchPanX = 0f; stitchPanY = 0f
                            lockedLeftBoundaryX = null; lockedRightBoundaryX = null
                            lockedLeftBoundaryY = null; lockedRightBoundaryY = null
                            showEditor()
                        }
                        1 -> { cropMode = !cropMode; showEditor() }
                        2 -> export()
                    }
                }
            }
            val glyphColor = when {
                isSave -> Color.WHITE
                isCropSelected -> 0xFF7158D8.toInt()
                else -> 0xFF586171.toInt()
            }
            val glyph: View = when(index) {
                0 -> iconImage(R.drawable.ic_rotate, glyphColor)
                1 -> iconImage(R.drawable.ic_crop, glyphColor)
                else -> ToolGlyph(this, 5, glyphColor)
            }
            item.addView(glyph, LinearLayout.LayoutParams(dp(26), dp(26)))
            item.addView(label(title, 11, if(isSave) Color.WHITE else if(isCropSelected) 0xFF7158D8.toInt() else 0xFF687181.toInt(), isSave || isCropSelected).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
            })
            bottom.addView(item, LinearLayout.LayoutParams(0, dp(58), if(isSave) 1.08f else 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        r.addView(bottom, LinearLayout.LayoutParams(-1, dp(76)))
    }

    private fun export() {
        val shown=photos.filter { it.visible }
        if(shown.size<2) { toast("至少保留 2 张显示中的图片"); return }
        try {
            val cross=1800f
            val sourceRects=mutableListOf<RectF>()
            val visibleRects=mutableListOf<RectF>()
            val slots=mutableListOf<Pair<Float,Float>>()
            val segments=StitchGeometry.calculate(shown.map { it.bitmap.width to it.bitmap.height },shown.map { it.shift },cross,horizontal)
            segments.forEach { segment ->
                if(horizontal) {
                    sourceRects.add(RectF(segment.imageStart,0f,segment.imageEnd,cross))
                    visibleRects.add(RectF(segment.visibleStart,0f,segment.visibleEnd,cross))
                } else {
                    sourceRects.add(RectF(0f,segment.imageStart,cross,segment.imageEnd))
                    visibleRects.add(RectF(0f,segment.visibleStart,cross,segment.visibleEnd))
                }
                slots.add(segment.slotStart to segment.slotEnd)
            }
            val previewCross=if(horizontal) preview.height.toFloat() else preview.width.toFloat()
            val previewAxis=if(horizontal) preview.width.toFloat() else preview.height.toFloat()
            val scale=cross/previewCross.coerceAtLeast(1f)
            val pan=(if(horizontal) stitchPanX else stitchPanY)*scale
            val viewportAxis=previewAxis*scale
            val imageOffset=pan
            val lockedLeft=if(horizontal) lockedLeftBoundaryX else lockedLeftBoundaryY
            val lockedRight=if(horizontal) lockedRightBoundaryX else lockedRightBoundaryY
            val lockedLeftScreen=lockedLeft?.let { it*scale+imageOffset }
            val lockedRightScreen=lockedRight?.let { it*scale+imageOffset }
            val screenRects=visibleRects.map { rect ->
                if(horizontal) RectF(maxOf(0f,lockedLeftScreen ?: 0f,rect.left+imageOffset),0f,minOf(viewportAxis,lockedRightScreen ?: viewportAxis,rect.right+imageOffset),cross)
                else RectF(0f,maxOf(0f,lockedLeftScreen ?: 0f,rect.top+imageOffset),cross,minOf(viewportAxis,lockedRightScreen ?: viewportAxis,rect.bottom+imageOffset))
            }.filter { if(horizontal) it.right>it.left else it.bottom>it.top }
            if(screenRects.isEmpty()) { toast("当前拼接内容都在隐藏区域外"); return }
            val cropStart=if(horizontal) screenRects.minOf { it.left } else screenRects.minOf { it.top }
            val cropEnd=if(horizontal) screenRects.maxOf { it.right } else screenRects.maxOf { it.bottom }
            val outW=if(horizontal) (cropEnd-cropStart).toInt().coerceAtLeast(1) else cross.toInt()
            val outH=if(horizontal) cross.toInt() else (cropEnd-cropStart).toInt().coerceAtLeast(1)
            if(outW>12000 || outH>12000) { toast("图片尺寸过大，请缩小拼接范围"); return }
            val out=Bitmap.createBitmap(outW.coerceAtLeast(1),outH.coerceAtLeast(1),Bitmap.Config.ARGB_8888); val c=Canvas(out); c.drawColor(Color.WHITE)
            shown.forEachIndexed { i,p ->
                val r=sourceRects[i]
                val dst=if(horizontal) RectF(r.left+imageOffset-cropStart,0f,r.right+imageOffset-cropStart,outH.toFloat()) else RectF(0f,r.top+imageOffset-cropStart,outW.toFloat(),r.bottom+imageOffset-cropStart)
                val (slotStart,slotEnd)=slots[i]
                c.save()
                if(horizontal) c.clipRect(if(i>0) slotStart+imageOffset-cropStart else 0f,0f,if(i<shown.lastIndex) slotEnd+imageOffset-cropStart else outW.toFloat(),outH.toFloat())
                else c.clipRect(0f,if(i>0) slotStart+imageOffset-cropStart else 0f,outW.toFloat(),if(i<shown.lastIndex) slotEnd+imageOffset-cropStart else outH.toFloat())
                if(horizontal) {
                    lockedLeftScreen?.let { c.clipRect(it-cropStart,0f,outW.toFloat(),outH.toFloat()) }
                    lockedRightScreen?.let { c.clipRect(0f,0f,it-cropStart,outH.toFloat()) }
                } else {
                    lockedLeftScreen?.let { c.clipRect(0f,it-cropStart,outW.toFloat(),outH.toFloat()) }
                    lockedRightScreen?.let { c.clipRect(0f,0f,outW.toFloat(),it-cropStart) }
                }
                c.drawBitmap(p.bitmap,null,dst,Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.restore()
            }
            val cropTopPx=(cropTop*preview.height*scale).toInt().coerceAtLeast(0)
            val cropBottomPx=(cropBottom*preview.height*scale).toInt().coerceAtLeast(0)
            val finalOut=if(cropTopPx+cropBottomPx<out.height) {
                Bitmap.createBitmap(out,0,cropTopPx,out.width,out.height-cropTopPx-cropBottomPx).also { out.recycle() }
            } else out
            val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"Stitch_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/liKePic拼图工坊") }
            val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("保存失败")
            contentResolver.openOutputStream(uri)?.use { finalOut.compress(Bitmap.CompressFormat.JPEG,95,it) }
            finalOut.recycle()
            status.text="liKePic拼图工坊 · 已保存到 Pictures/liKePic拼图工坊"; toast("liKePic拼图工坊已保存到相册")
        } catch(e:Exception) { toast("保存失败：${e.message}") }
    }

    private fun recordAppVisit() {
        galleryExecutor.execute {
            val connection = runCatching {
                (URL("https://keji.likeyuns.cn/likePic/open.php").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "liKePic-Android")
                }
            }.getOrNull() ?: return@execute
            try {
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.use { it.readBytes() }
            } catch (_: Exception) {
                // Visit recording is best-effort and must never block app startup.
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun decodePhoto(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private inner class StitchPreview(context: android.content.Context, var photos: List<Photo>, var horizontal: Boolean) : View(context) {
        var anchorGravity:Int=Gravity.CENTER
        var expandedWidth=0
            private set
        var expandedHeight=0
            private set
        var cropMode=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val handlePaint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val handleIconPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=0xFF704000.toInt(); style=Paint.Style.STROKE; strokeWidth=dp(2f)
            strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
        }
        private var bounds=mutableListOf<RectF>()
        private var seamPositions=mutableListOf<Float>()
        private var dragging=-1
        private var lastCoord=0f
        private var lastRawDown=0f
        private var downCoord=0f
        private var didMove=false
        private var gestureBefore: EditSnapshot? = null
        private var gestureChanged=false
        private var seamTarget=-1
        private var contentMinX=0f
        private var contentMaxX=0f
        private var contentMinY=0f
        private var contentMaxY=0f
        fun setExpandedFrame(frameWidth:Int,frameHeight:Int) {
            // A locked composition may be narrower than the original strip. Keep
            // that committed crop as the next editing frame when the editor view
            // is rebuilt (for example when switching tabs).
            val committedWidth = if(horizontal && lockedLeftBoundaryX!=null && lockedRightBoundaryX!=null)
                kotlin.math.abs(lockedRightBoundaryX!!-lockedLeftBoundaryX!!).toInt().coerceAtLeast(1)
            else frameWidth.coerceAtLeast(1)
            val committedHeight = if(!horizontal && lockedLeftBoundaryY!=null && lockedRightBoundaryY!=null)
                kotlin.math.abs(lockedRightBoundaryY!!-lockedLeftBoundaryY!!).toInt().coerceAtLeast(1)
            else frameHeight.coerceAtLeast(1)
            expandedWidth=committedWidth
            expandedHeight=committedHeight
            ensureLockedBoundaries()
            layoutParams=FrameLayout.LayoutParams(expandedWidth,expandedHeight,Gravity.CENTER)
        }
        private fun ensureLockedBoundaries() {
            val frameWidth=expandedWidth.takeIf { it>0 }?.toFloat() ?: width.toFloat()
            val frameHeight=expandedHeight.takeIf { it>0 }?.toFloat() ?: height.toFloat()
            if(horizontal) {
                if(leftEdgeLocked && lockedLeftBoundaryX==null) lockedLeftBoundaryX=-stitchPanX
                if(rightEdgeLocked && lockedRightBoundaryX==null) lockedRightBoundaryX=frameWidth-stitchPanX
            } else {
                if(leftEdgeLocked && lockedLeftBoundaryY==null) lockedLeftBoundaryY=-stitchPanY
                if(rightEdgeLocked && lockedRightBoundaryY==null) lockedRightBoundaryY=frameHeight-stitchPanY
            }
        }
        fun restoreExpandedFrame(frameWidth: Int? = null, frameHeight: Int? = null) {
            if(frameWidth != null) expandedWidth=frameWidth.coerceAtLeast(1)
            if(frameHeight != null) expandedHeight=frameHeight.coerceAtLeast(1)
            if(expandedWidth<=0 || expandedHeight<=0) return
            anchorGravity=Gravity.CENTER
            layoutParams=FrameLayout.LayoutParams(expandedWidth,expandedHeight,anchorGravity)
            invalidate()
        }
        fun finalizeVisibleComposition() {
            if(width<=0 || height<=0) return
            val shown=photos.filter { it.visible }
            if(shown.isEmpty()) return
            val crossSize=if(horizontal) height.toFloat() else width.toFloat()
            val viewport=if(horizontal) width.toFloat() else height.toFloat()
            val segments=StitchGeometry.calculate(shown.map { it.bitmap.width to it.bitmap.height },shown.map { it.shift },crossSize,horizontal)
            val range=StitchGeometry.visibleRange(
                segments,
                if(horizontal) stitchPanX else stitchPanY,
                viewport,
                if(horizontal) lockedLeftBoundaryX else lockedLeftBoundaryY,
                if(horizontal) lockedRightBoundaryX else lockedRightBoundaryY
            ) ?: return
            val extent=range.length.toInt().coerceAtLeast(1)
            val params=layoutParams as? FrameLayout.LayoutParams ?: return
            anchorGravity=Gravity.CENTER
            if(horizontal) {
                stitchPanX-=range.start
                params.width=extent
                expandedWidth=extent
            } else {
                stitchPanY-=range.start
                params.height=extent
                expandedHeight=extent
            }
            params.gravity=anchorGravity
            layoutParams=params
            invalidate()
        }
        private fun seamCoordinate(index:Int):Float {
            seamPositions.getOrNull(index)?.let { return it }
            if(horizontal) return (bounds[index].right+bounds[index+1].left)/2f
            return (bounds[index].bottom+bounds[index+1].top)/2f
        }
        private fun seamHandleCenter(index:Int, avoidEdgeHandles:Boolean):Pair<Float,Float> {
            val seam=seamCoordinate(index)
            if(horizontal) return seam to height/2f
            val topHandleY=dp(14f)
            val bottomHandleY=height-dp(14f)
            val nearTop=kotlin.math.abs(seam-topHandleY)<=dp(58f)
            val nearBottom=kotlin.math.abs(seam-bottomHandleY)<=dp(58f)
            val x=if(avoidEdgeHandles && nearTop && (!nearBottom || kotlin.math.abs(seam-topHandleY)<=kotlin.math.abs(seam-bottomHandleY))) {
                width-dp(24f)
            } else if(avoidEdgeHandles && nearBottom) {
                dp(24f)
            } else width/2f
            return x to seam
        }
        fun edgeBoundary(left: Boolean): Float = if(horizontal) {
            (if(left) 0f else width.toFloat()) - stitchPanX
        } else {
            (if(left) 0f else height.toFloat()) - stitchPanY
        }
        fun updateExtent(shown: List<Photo> = photos.filter { it.visible }) {
            if (shown.isEmpty() || expandedWidth <= 0 || expandedHeight <= 0) return
            // A locked outer edge must stay attached to the neighboring image.
            // Otherwise dragging the first/last photo outward opens a blank seam.
            if (shown.size > 1) {
                if (leftEdgeLocked) shown.first().shift = maxOf(shown.first().shift, 0f)
                if (rightEdgeLocked) shown.last().shift = minOf(shown.last().shift, 0f)
            }
            val params = layoutParams as? FrameLayout.LayoutParams ?: return
            if(controlLocks.activeControl()!=null) {
                if(horizontal && expandedWidth>0) params.width=expandedWidth
                else if(!horizontal && expandedHeight>0) params.height=expandedHeight
            } else {
                // Use the requested frame dimensions. The View can still report
                // its temporary 320dp layout size before the first layout pass;
                // using that size here would truncate a vertical strip to 320dp.
                val crossSize=if(horizontal) expandedHeight.toFloat() else expandedWidth.toFloat()
                val viewport=if(horizontal) expandedWidth.toFloat() else expandedHeight.toFloat()
                val segments=StitchGeometry.calculate(shown.map { it.bitmap.width to it.bitmap.height },shown.map { it.shift },crossSize,horizontal)
                val range=StitchGeometry.visibleRange(
                    segments,
                    if(horizontal) stitchPanX else stitchPanY,
                    viewport,
                    if(horizontal) lockedLeftBoundaryX else lockedLeftBoundaryY,
                    if(horizontal) lockedRightBoundaryX else lockedRightBoundaryY
                )
                val extent=range?.length?.toInt()?.coerceAtLeast(1) ?: 1
                anchorGravity=Gravity.CENTER
                params.gravity=anchorGravity
                if(horizontal) {
                    params.width=extent
                    expandedWidth=extent
                } else {
                    params.height=extent
                    expandedHeight=extent
                }
            }
            ensureLockedBoundaries()
            layoutParams=params
            requestLayout()
            invalidate()
        }
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            val shown=photos.filter { it.visible }
            bounds=mutableListOf()
            seamPositions=mutableListOf()
            if(shown.isEmpty()) return
            if (shown.size > 1) {
                if (leftEdgeLocked) shown.first().shift = maxOf(shown.first().shift, 0f)
                if (rightEdgeLocked) shown.last().shift = minOf(shown.last().shift, 0f)
            }
            controlLocks.ensureSeams((shown.size-1).coerceAtLeast(0))
            val crossSize=if(horizontal) height.toFloat() else width.toFloat()
            val segments=StitchGeometry.calculate(shown.map { it.bitmap.width to it.bitmap.height },shown.map { it.shift },crossSize,horizontal)
            val imageRects=segments.map { segment ->
                if(horizontal) RectF(segment.imageStart,0f,segment.imageEnd,height.toFloat())
                else RectF(0f,segment.imageStart,width.toFloat(),segment.imageEnd)
            }
            segments.forEach { segment ->
                bounds.add(if(horizontal) RectF(segment.visibleStart,0f,segment.visibleEnd,height.toFloat())
                    else RectF(0f,segment.visibleStart,width.toFloat(),segment.visibleEnd))
            }
            val minX=bounds.minOf { it.left }; val maxX=bounds.maxOf { it.right }
            val minY=bounds.minOf { it.top }; val maxY=bounds.maxOf { it.bottom }
            contentMinX=minX; contentMaxX=maxX; contentMinY=minY; contentMaxY=maxY
            val offsetX=stitchPanX
            val offsetY=stitchPanY
            // The seam handle belongs to the fixed slot boundary. Image bounds
            // move inside their slots while dragging, so deriving the handle from
            // visible image edges makes it drift away from the actual divider.
            seamPositions=segments.drop(1).map { segment ->
                // Later photos are drawn over any overlap, so the visible seam
                // is the next photo's leading slot edge, not the previous photo's
                // trailing edge.
                if(horizontal) segment.slotStart+offsetX else segment.slotStart+offsetY
            }.toMutableList()
            if(cropMode) {
                c.save()
                c.clipRect(0f,cropTop*height,width.toFloat(),height*(1f-cropBottom))
            }
            bounds.indices.forEach { i ->
                val source=imageRects[i]
                val rect=if(horizontal) RectF(source.left+offsetX,source.top,source.right+offsetX,source.bottom) else RectF(source.left,source.top+offsetY,source.right,source.bottom+offsetY)
                val slotStart=segments[i].slotStart
                val slotSize=segments[i].slotEnd-segments[i].slotStart
                c.save()
                if(horizontal) c.clipRect(if(i>0) slotStart+offsetX else 0f,0f,if(i<shown.lastIndex) slotStart+slotSize+offsetX else width.toFloat(),height.toFloat())
                else c.clipRect(0f,if(i>0) slotStart+offsetY else 0f,width.toFloat(),if(i<shown.lastIndex) slotStart+slotSize+offsetY else height.toFloat())
                if(horizontal) {
                    lockedLeftBoundaryX?.let { c.clipRect(it+offsetX,0f,width.toFloat(),height.toFloat()) }
                    lockedRightBoundaryX?.let { c.clipRect(0f,0f,it+offsetX,height.toFloat()) }
                } else {
                    lockedLeftBoundaryY?.let { c.clipRect(0f,it+offsetY,width.toFloat(),height.toFloat()) }
                    lockedRightBoundaryY?.let { c.clipRect(0f,0f,width.toFloat(),it+offsetY) }
                }
                c.drawBitmap(shown[i].bitmap,null,rect,paint)
                c.restore()
                val visible=bounds[i]
                bounds[i]=if(horizontal) RectF(visible.left+offsetX,visible.top,visible.right+offsetX,visible.bottom) else RectF(visible.left,visible.top+offsetY,visible.right,visible.bottom)
            }
            if(cropMode) c.restore()
            // Draw handles last so the next image can never cover them.
            fun handle(cx:Float,cy:Float,side:Boolean,arrow:String,compact:Boolean=false) {
                val halfW=dp(if(horizontal) if(side) 10f else 11f else if(compact) 14f else 25f)
                val halfH=dp(if(horizontal) if(side) 25f else 26f else 11f)
                val handleRect=RectF(cx-halfW,cy-halfH,cx+halfW,cy+halfH)
                val radius=dp(9f)
                // A restrained shadow and fine highlight keep the orange control crisp
                // without the oversized, toy-like button appearance.
                handlePaint.style=Paint.Style.FILL
                handlePaint.color=0x22000000
                c.drawRoundRect(RectF(handleRect.left,handleRect.top+dp(1.5f),handleRect.right,handleRect.bottom+dp(1.5f)),radius,radius,handlePaint)
                handlePaint.color=0xFFFFB83F.toInt()
                c.drawRoundRect(handleRect,radius,radius,handlePaint)
                handlePaint.color=0x66FFF0C8
                handlePaint.style=Paint.Style.STROKE
                handlePaint.strokeWidth=dp(1f)
                c.drawRoundRect(RectF(handleRect.left+dp(.5f),handleRect.top+dp(.5f),handleRect.right-dp(.5f),handleRect.bottom-dp(.5f)),radius,radius,handlePaint)
                handlePaint.style=Paint.Style.FILL
                handleIconPaint.color=0xFF704000.toInt()
                handleIconPaint.strokeWidth=dp(2f)
                fun line(x1:Float,y1:Float,x2:Float,y2:Float) = c.drawLine(x1,y1,x2,y2,handleIconPaint)
                fun arrowHead(x:Float,y:Float,dx:Float,dy:Float) {
                    val size=dp(3.5f)
                    val px=-dy; val py=dx
                    line(x-dx*size+px*size*.72f,y-dy*size+py*size*.72f,x,y)
                    line(x-dx*size-px*size*.72f,y-dy*size-py*size*.72f,x,y)
                }
                fun arrowAt(x:Float,y:Float,dx:Float,dy:Float) {
                    val half=dp(6f)
                    val tipX=x+dx*half; val tipY=y+dy*half
                    line(x-dx*half,y-dy*half,tipX,tipY)
                    arrowHead(tipX,tipY,dx,dy)
                }
                if(!side && horizontal) {
                    // Picsew-style opposing arrows: top points right, bottom points left.
                    arrowAt(cx,cy-dp(4f),1f,0f)
                    arrowAt(cx,cy+dp(4f),-1f,0f)
                } else if(!side) {
                    arrowAt(cx-dp(4f),cy,0f,-1f)
                    arrowAt(cx+dp(4f),cy,0f,1f)
                } else {
                    when(arrow) {
                        "←" -> arrowAt(cx,cy,-1f,0f)
                        "→" -> arrowAt(cx,cy,1f,0f)
                        "↑" -> arrowAt(cx,cy,0f,-1f)
                        else -> arrowAt(cx,cy,0f,1f)
                    }
                }
                handlePaint.style=Paint.Style.FILL
                handleIconPaint.style=Paint.Style.STROKE
            }
            if(cropMode) {
                fun cropBar(y:Float) {
                    val bar=RectF(dp(7f),y-dp(2f),width-dp(7f),y+dp(2f))
                    handlePaint.color=0xCCFFB83F.toInt(); handlePaint.style=Paint.Style.FILL
                    c.drawRoundRect(bar,dp(2f),dp(2f),handlePaint)
                    val cy=y.coerceIn(dp(10f),height-dp(10f))
                    val grip=RectF(width/2f-dp(22f),cy-dp(10f),width/2f+dp(22f),cy+dp(10f))
                    handlePaint.color=0xFFFFB83F.toInt(); c.drawRoundRect(grip,dp(9f),dp(9f),handlePaint)
                    handlePaint.color=0xFFFFEAC1.toInt(); handlePaint.style=Paint.Style.STROKE; handlePaint.strokeWidth=dp(1f)
                    c.drawRoundRect(RectF(grip.left+dp(.5f),grip.top+dp(.5f),grip.right-dp(.5f),grip.bottom-dp(.5f)),dp(9f),dp(9f),handlePaint)
                    handlePaint.style=Paint.Style.FILL
                    handleIconPaint.color=0xFF704000.toInt(); handleIconPaint.strokeWidth=dp(2f)
                    val cx=width/2f; val tip=dp(6f); val spread=dp(3.5f)
                    c.drawLine(cx,cy-tip,cx,cy+tip,handleIconPaint)
                    c.drawLine(cx-spread,cy-tip+spread,cx,cy-tip,handleIconPaint)
                    c.drawLine(cx+spread,cy-tip+spread,cx,cy-tip,handleIconPaint)
                    c.drawLine(cx-spread,cy+tip-spread,cx,cy+tip,handleIconPaint)
                    c.drawLine(cx+spread,cy+tip-spread,cx,cy+tip,handleIconPaint)
                }
                cropBar(cropTop*height)
                cropBar((1f-cropBottom)*height)
            } else {
                when(controlLocks.activeControl()) {
                    ActiveControl.LEFT_EDGE -> if(horizontal) handle(dp(8f),height/2f,true,"←") else handle(width/2f,dp(14f),true,"↑")
                    ActiveControl.RIGHT_EDGE -> if(horizontal) handle(width-dp(8f),height/2f,true,"→") else handle(width/2f,height-dp(14f),true,"↓")
                    ActiveControl.CENTER_SEAM -> {
                        val index=controlLocks.activeSeamIndex()
                        if(index in bounds.indices) {
                            val (cx,cy)=seamHandleCenter(index,avoidEdgeHandles=false)
                            handle(cx,cy,false,if(horizontal) "↔" else "↕")
                        }
                    }
                    null -> {
                        for(i in 0 until (bounds.size-1).coerceAtLeast(0)) {
                            val (cx,cy)=seamHandleCenter(i,avoidEdgeHandles=true)
                            val compact=!horizontal && kotlin.math.abs(cx-width/2f)>dp(1f)
                            handle(cx,cy,false,if(horizontal) "↔" else "↕",compact)
                        }
                        if(horizontal) {
                            handle(dp(8f),height/2f,true,"←")
                            handle(width-dp(8f),height/2f,true,"→")
                        } else {
                            handle(width/2f,dp(14f),true,"↑")
                            handle(width/2f,height-dp(14f),true,"↓")
                        }
                    }
                }
            }
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            val shown=photos.filter { it.visible }
            val coord=if(horizontal) e.x else e.y
            val axis=if(horizontal) width.toFloat() else height.toFloat()
            if(e.action==MotionEvent.ACTION_DOWN) {
                downCoord=coord; lastRawDown=if(horizontal) e.rawX else e.rawY; lastCoord=lastRawDown; didMove=false
                gestureBefore=null; gestureChanged=false; pendingEdgeTapToLock=null
                if(cropMode) {
                    val topY=cropTop*height
                    val bottomY=(1f-cropBottom)*height
                    dragging=when {
                        kotlin.math.abs(e.y-topY)<=dp(20f) -> -5
                        kotlin.math.abs(e.y-bottomY)<=dp(20f) -> -6
                        else -> -7
                    }
                    gestureBefore=captureSnapshot(); lastRawDown=e.rawY; lastCoord=e.rawY
                    return true
                }
                val cross=if(horizontal) e.y else e.x
                val crossCenter=if(horizontal) height/2f else width/2f
                val activeControl=controlLocks.activeControl()
                // Keep lock hit targets aligned with the visible edge handles so
                // tapping the adjacent top/bottom image still starts an image drag.
                val onControlBand=kotlin.math.abs(cross-crossCenter)<=dp(34f)
                seamTarget=(0 until (bounds.size-1).coerceAtLeast(0)).minByOrNull { index ->
                    val (handleX,handleY)=seamHandleCenter(index,avoidEdgeHandles=activeControl==null)
                    val dx=if(horizontal) 0f else e.x-handleX
                    val dy=if(horizontal) coord-seamCoordinate(index) else e.y-handleY
                    dx*dx+dy*dy
                } ?: -1
                val seamDistance=if(seamTarget>=0) {
                    val (handleX,handleY)=seamHandleCenter(seamTarget,avoidEdgeHandles=activeControl==null)
                    val dx=if(horizontal) 0f else e.x-handleX
                    val dy=if(horizontal) coord-seamCoordinate(seamTarget) else e.y-handleY
                    kotlin.math.sqrt(dx*dx+dy*dy)
                } else Float.MAX_VALUE
                val leftHandleVisible=activeControl==null || activeControl==ActiveControl.LEFT_EDGE
                val rightHandleVisible=activeControl==null || activeControl==ActiveControl.RIGHT_EDGE
                val seamHandleVisible=activeControl==null || activeControl==ActiveControl.CENTER_SEAM
                val seamHandleHit=seamTarget>=0 && seamHandleVisible && if(horizontal) {
                    onControlBand && seamDistance<=dp(16f)
                } else {
                    seamDistance<=dp(32f)
                }
                dragging=when {
                    onControlBand && leftHandleVisible && coord<dp(28f) -> -2
                    onControlBand && rightHandleVisible && coord>axis-dp(28f) -> -3
                    // Keep the seam toggle target close to its visible handle so
                    // most of each photo remains available for direct dragging.
                    seamHandleHit -> -4
                    else -> bounds.indices.lastOrNull { i -> bounds[i].contains(e.x,e.y) }
                        ?: -1
                }
                if(dragging>=0) gestureBefore=captureSnapshot()
                // On a locked edge handle, unlock immediately so dragging from the
                // handle itself can pan. On an already-unlocked handle, wait until
                // ACTION_UP to distinguish a tap-to-lock from a drag-to-adjust.
                if(dragging==-2) {
                    if(activeControl==ActiveControl.LEFT_EDGE) {
                        pendingEdgeTapToLock=ActiveControl.LEFT_EDGE
                        gestureBefore=captureSnapshot()
                    } else {
                        toggleEdgeControl(left = true)
                        gestureBefore=captureSnapshot()
                    }
                }
                if(dragging==-3) {
                    if(activeControl==ActiveControl.RIGHT_EDGE) {
                        pendingEdgeTapToLock=ActiveControl.RIGHT_EDGE
                        gestureBefore=captureSnapshot()
                    } else {
                        toggleEdgeControl(left = false)
                        gestureBefore=captureSnapshot()
                    }
                }
                return dragging>=0 || dragging==-2 || dragging==-3 || dragging==-4
            }
            if(e.action==MotionEvent.ACTION_MOVE && (dragging>=0 || dragging==-2 || dragging==-3 || dragging==-4 || dragging==-5 || dragging==-6)) {
                val rawCoord=if(cropMode) e.rawY else if(horizontal) e.rawX else e.rawY
                if(kotlin.math.abs(rawCoord-lastRawDown)>dp(4f)) didMove=true
                if(cropMode && didMove && (dragging==-5 || dragging==-6)) {
                    val delta=(rawCoord-lastCoord)/height.toFloat().coerceAtLeast(1f)
                    if(dragging==-5) {
                        val next=(cropTop+delta).coerceIn(0f,(.8f-cropBottom).coerceAtLeast(0f))
                        if(next!=cropTop) gestureChanged=true
                        cropTop=next
                    } else {
                        val next=(cropBottom-delta).coerceIn(0f,(.8f-cropTop).coerceAtLeast(0f))
                        if(next!=cropBottom) gestureChanged=true
                        cropBottom=next
                    }
                    lastCoord=rawCoord
                    invalidate(); return true
                }
                if(didMove && (dragging>=0 || dragging==-2 || dragging==-3)) {
                    val delta=(rawCoord-lastCoord)/(if(horizontal) height.toFloat() else width.toFloat()).coerceAtLeast(1f)
                    val activeControl=controlLocks.activeControl()
                    if(activeControl==ActiveControl.LEFT_EDGE || activeControl==ActiveControl.RIGHT_EDGE) {
                        val panDelta=rawCoord-lastCoord
                        val expandedAxis=(if(horizontal) expandedWidth else expandedHeight).toFloat().coerceAtLeast(axis)
                        val maxPan=(expandedAxis-dp(24f)).coerceAtLeast(0f)
                        if(horizontal) {
                            val next=StitchGeometry.clampEdgePan(activeControl==ActiveControl.LEFT_EDGE,stitchPanX,panDelta,maxPan,edgePanAnchor)
                            if(next!=stitchPanX) gestureChanged=true
                            stitchPanX=next
                        } else {
                            val next=StitchGeometry.clampEdgePan(activeControl==ActiveControl.LEFT_EDGE,stitchPanY,panDelta,maxPan,edgePanAnchor)
                            if(next!=stitchPanY) gestureChanged=true
                            stitchPanY=next
                        }
                        invalidate()
                    } else if(activeControl==ActiveControl.CENTER_SEAM) {
                        val seamIndex=controlLocks.activeSeamIndex()
                        if(seamIndex>=0 && (dragging==seamIndex || dragging==seamIndex+1)) {
                            val crossSize=(if(horizontal) height.toFloat() else width.toFloat()).coerceAtLeast(1f)
                            val movingLeftGroup=dragging==seamIndex
                            val groupIndices=if(movingLeftGroup) 0..seamIndex else (seamIndex+1)..shown.lastIndex
                            val leftShift=shown[seamIndex].shift
                            val rightShift=shown[seamIndex+1].shift
                            val relativeShift=if(movingLeftGroup) leftShift-rightShift else rightShift-leftShift
                            val imageExtent=if(horizontal)
                                crossSize*shown[dragging].bitmap.width/shown[dragging].bitmap.height
                            else crossSize*shown[dragging].bitmap.height/shown[dragging].bitmap.width
                            val maxTravel=((imageExtent-dp(2f)).coerceAtLeast(0f)/crossSize)
                            val candidate=relativeShift+delta
                            val nextRelativeShift=if(movingLeftGroup) candidate.coerceIn(0f,maxTravel)
                                else candidate.coerceIn(-maxTravel,0f)
                            val groupDelta=nextRelativeShift-relativeShift
                            if(groupDelta!=0f) {
                                groupIndices.forEach { shown[it].shift+=groupDelta }
                                gestureChanged=true
                                invalidate()
                            }
                        }
                    }
                    lastCoord=rawCoord
                }
                invalidate(); return true
            }
            if(e.action==MotionEvent.ACTION_UP) {
                if(!didMove) {
                    when(pendingEdgeTapToLock) {
                        ActiveControl.LEFT_EDGE -> toggleEdgeControl(left = true)
                        ActiveControl.RIGHT_EDGE -> toggleEdgeControl(left = false)
                        else -> Unit
                    }
                }
                if(!didMove && dragging==-4 && seamTarget in seamLocks.indices) {
                    toggleSeamControl(seamTarget)
                }
                if(gestureChanged) gestureBefore?.let { recordUndo(it) }
                gestureBefore=null; gestureChanged=false; pendingEdgeTapToLock=null
                dragging=-1; seamTarget=-1; invalidate(); performClick(); return true
            }
            if(e.action==MotionEvent.ACTION_CANCEL) {
                if(gestureChanged) gestureBefore?.let { recordUndo(it) }
                gestureBefore=null; gestureChanged=false; pendingEdgeTapToLock=null
                dragging=-1; seamTarget=-1; invalidate(); return true
            }
            return true
        }
        override fun performClick():Boolean { super.performClick(); return true }
    }

    private inner class ToolGlyph(context: android.content.Context, private val kind:Int, private val tint:Int) : View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE
            strokeWidth=dp(1.8f)
            strokeCap=Paint.Cap.ROUND
            strokeJoin=Paint.Join.ROUND
        }
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            paint.color=tint
            val cx=width/2f; val cy=height/2f; val s=minOf(width,height)*.36f
            when(kind) {
                1 -> {
                    val d=s; val k=s*.38f
                    c.drawLine(cx-d,cy-d,cx-d+k,cy-d,paint); c.drawLine(cx-d,cy-d,cx-d,cy-d+k,paint)
                    c.drawLine(cx+d,cy-d,cx+d-k,cy-d,paint); c.drawLine(cx+d,cy-d,cx+d,cy-d+k,paint)
                    c.drawLine(cx-d,cy+d,cx-d+k,cy+d,paint); c.drawLine(cx-d,cy+d,cx-d,cy+d-k,paint)
                    c.drawLine(cx+d,cy+d,cx+d-k,cy+d,paint); c.drawLine(cx+d,cy+d,cx+d,cy+d-k,paint)
                }
                4 -> {
                    val path=Path().apply {
                        moveTo(cx-s,cy-s*.38f); lineTo(cx+s*.48f,cy-s*.38f)
                        moveTo(cx+s*.48f,cy-s*.38f); lineTo(cx+s*.12f,cy-s*.72f)
                        moveTo(cx+s*.48f,cy-s*.38f); lineTo(cx+s*.12f,cy-s*.04f)
                        moveTo(cx+s*.38f,cy+s); lineTo(cx+s*.38f,cy-s*.48f)
                        moveTo(cx+s*.38f,cy-s*.48f); lineTo(cx+s*.04f,cy-s*.12f)
                        moveTo(cx+s*.38f,cy-s*.48f); lineTo(cx+s*.72f,cy-s*.12f)
                    }
                    c.drawPath(path,paint)
                }
                5 -> {
                    val path=Path().apply {
                        moveTo(cx-s*.72f,cy+s*.22f); lineTo(cx-s*.72f,cy+s*.68f)
                        lineTo(cx+s*.72f,cy+s*.68f); lineTo(cx+s*.72f,cy+s*.22f)
                        moveTo(cx,cy-s*.72f); lineTo(cx,cy+s*.28f)
                        moveTo(cx-s*.38f,cy-s*.08f); lineTo(cx,cy+s*.30f); lineTo(cx+s*.38f,cy-s*.08f)
                    }
                    c.drawPath(path,paint)
                }
                else -> {
                    val gap=s*.38f
                    c.drawLine(cx-gap,cy-s,cx-gap,cy+s,paint)
                    c.drawLine(cx+gap,cy-s,cx+gap,cy+s,paint)
                    c.drawLine(cx-s,cy,cx+s,cy,paint)
                }
            }
        }
    }

    private fun iconImage(resourceId:Int,tint:Int)=ImageView(this).apply {
        setImageResource(resourceId)
        imageTintList=ColorStateList.valueOf(tint)
        scaleType=ImageView.ScaleType.FIT_CENTER
    }
    private fun button(text:String,primary:Boolean,action:()->Unit)=TextView(this).apply { this.text=text; textSize=15f; typeface=android.graphics.Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setTextColor(if(primary) Color.WHITE else 0xFF655E71.toInt()); background=rounded(if(primary) 0xFF7559E8.toInt() else 0xFFF0EEF5.toInt(),14); setOnClickListener { action() } }
    private fun label(text:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply { this.text=text; textSize=size.toFloat(); setTextColor(color); if(bold) typeface=android.graphics.Typeface.DEFAULT_BOLD }
    private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun dp(v:Float)=v*resources.displayMetrics.density
    private fun margin(l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(l),dp(t),dp(r),dp(b)) }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
