package com.example.screenstitch

/** Axis-aligned source and slot geometry shared by preview and export. */
internal data class StitchSegment(
    val slotStart: Float,
    val slotEnd: Float,
    val imageStart: Float,
    val imageEnd: Float,
    val visibleStart: Float,
    val visibleEnd: Float
)

internal data class StitchRange(val start: Float, val end: Float) {
    val length: Float get() = (end - start).coerceAtLeast(0f)
}

internal enum class ActiveControl { LEFT_EDGE, CENTER_SEAM, RIGHT_EDGE }

/** Exactly one handle may be unlocked at a time. */
internal class StitchControlLocks {
    var leftLocked: Boolean = true
    var rightLocked: Boolean = true
    val seamLocks = mutableListOf<Boolean>()

    fun ensureSeams(count: Int) {
        while (seamLocks.size < count) seamLocks.add(true)
        while (seamLocks.size > count) seamLocks.removeAt(seamLocks.lastIndex)
    }

    fun lockAll() {
        leftLocked = true
        rightLocked = true
        seamLocks.indices.forEach { seamLocks[it] = true }
    }

    fun toggleLeft() = toggleEdge(left = true)
    fun toggleRight() = toggleEdge(left = false)
    fun toggleSeam(index: Int) {
        if (index !in seamLocks.indices) return
        val unlock = seamLocks[index]
        lockAll()
        if (unlock) seamLocks[index] = false
    }

    private fun toggleEdge(left: Boolean) {
        val unlock = if (left) leftLocked else rightLocked
        lockAll()
        if (unlock) {
            if (left) leftLocked = false else rightLocked = false
        }
    }

    fun activeControl(): ActiveControl? = when {
        !leftLocked -> ActiveControl.LEFT_EDGE
        !rightLocked -> ActiveControl.RIGHT_EDGE
        seamLocks.any { !it } -> ActiveControl.CENTER_SEAM
        else -> null
    }

    fun activeSeamIndex(): Int = seamLocks.indexOfFirst { !it }
}

internal object StitchGeometry {
    fun calculate(
        sizes: List<Pair<Int, Int>>,
        shifts: List<Float>,
        crossSize: Float,
        horizontal: Boolean
    ): List<StitchSegment> {
        require(sizes.size == shifts.size)
        if (sizes.isEmpty() || crossSize <= 0f) return emptyList()
        var cursor = 0f
        return sizes.mapIndexed { index, (width, height) ->
            require(width > 0 && height > 0)
            val extent = crossSize * if (horizontal) width.toFloat() / height else height.toFloat() / width
            // A photo shift moves its stitched slot as well as its bitmap. When a
            // seam is adjusted with 3+ photos, the whole side group moves together;
            // keeping slots fixed would leave a blank gap before the next photo.
            val baseStart = cursor
            val slotStart = baseStart + shifts[index] * crossSize
            val slotEnd = slotStart + extent
            val imageStart = slotStart
            val imageEnd = imageStart + extent
            val segment = StitchSegment(
                slotStart = slotStart,
                slotEnd = slotEnd,
                imageStart = imageStart,
                imageEnd = imageEnd,
                visibleStart = if (index == 0) imageStart else maxOf(imageStart, slotStart),
                visibleEnd = if (index == sizes.lastIndex) imageEnd else minOf(imageEnd, slotEnd)
            )
            cursor = baseStart + extent
            segment
        }
    }

    /** Returns the non-blank part of the stitched strip inside its viewport. */
    fun visibleRange(
        segments: List<StitchSegment>,
        pan: Float,
        viewport: Float,
        lockedStart: Float? = null,
        lockedEnd: Float? = null
    ): StitchRange? {
        if (segments.isEmpty() || viewport <= 0f) return null
        val start = maxOf(0f, segments.minOf { it.visibleStart } + pan, lockedStart?.plus(pan) ?: 0f)
        val end = minOf(viewport, segments.maxOf { it.visibleEnd } + pan, lockedEnd?.plus(pan) ?: viewport)
        return if (end > start) StitchRange(start, end) else null
    }

    fun clampTowardNeighbor(
        shift: Float,
        delta: Float,
        index: Int,
        lastIndex: Int,
        imageExtent: Float,
        crossSize: Float,
        minimumVisible: Float
    ): Float {
        if (crossSize <= 0f) return shift
        val maxShift = (imageExtent - minimumVisible).coerceAtLeast(0f) / crossSize
        return when {
            delta > 0f && index < lastIndex -> minOf(shift, maxShift)
            delta < 0f && index > 0 -> maxOf(shift, -maxShift)
            else -> shift
        }
    }

    fun clampForSeamDrag(
        shift: Float,
        delta: Float,
        isLeftPhoto: Boolean,
        imageExtent: Float,
        crossSize: Float,
        minimumVisible: Float
    ): Float {
        if (crossSize <= 0f) return shift
        val maxShift = (imageExtent - minimumVisible).coerceAtLeast(0f) / crossSize
        val candidate = shift + delta
        return if (isLeftPhoto) candidate.coerceIn(0f, maxShift) else candidate.coerceIn(-maxShift, 0f)
    }

    fun clampEdgePan(
        leftEdge: Boolean,
        pan: Float,
        delta: Float,
        maxTravel: Float,
        anchor: Float = 0f
    ): Float {
        val travel = maxTravel.coerceAtLeast(0f)
        // The left handle moves the collage toward the left edge; the right
        // handle moves it toward the right edge. Reversing a prior adjustment
        // remains possible, but stops at the original edge so this handle cannot
        // push the opposite side of the collage into its hidden area.
        val minPan = if (leftEdge) anchor - travel else minOf(anchor, 0f)
        val maxPan = if (leftEdge) maxOf(anchor, 0f) else anchor + travel
        return (pan + delta).coerceIn(minPan, maxPan)
    }
}
