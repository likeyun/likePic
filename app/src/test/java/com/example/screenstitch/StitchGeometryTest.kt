package com.example.screenstitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StitchGeometryTest {
    @Test fun controlsStartLockedAndUnlockExclusively() {
        val locks=StitchControlLocks().apply { ensureSeams(1) }
        assertNull(locks.activeControl())

        locks.toggleLeft()
        assertEquals(ActiveControl.LEFT_EDGE,locks.activeControl())
        assertEquals(listOf(true),locks.seamLocks)
        assertEquals(true,locks.rightLocked)

        locks.toggleSeam(0)
        assertEquals(ActiveControl.CENTER_SEAM,locks.activeControl())
        assertEquals(true,locks.leftLocked)
        assertEquals(true,locks.rightLocked)
        assertEquals(listOf(false),locks.seamLocks)

        locks.toggleSeam(0)
        assertNull(locks.activeControl())
        assertEquals(true,locks.leftLocked)
        assertEquals(true,locks.rightLocked)
        assertEquals(listOf(true),locks.seamLocks)
    }

    @Test fun addingSeamsDoesNotUnlockNewControls() {
        val locks=StitchControlLocks()
        locks.ensureSeams(1)
        locks.toggleSeam(0)
        locks.ensureSeams(3)
        assertEquals(listOf(false,true,true),locks.seamLocks)
        assertEquals(ActiveControl.CENTER_SEAM,locks.activeControl())
    }

    @Test fun seamDragKeepsDividerContinuousAndImageScaleFixed() {
        val sizes=listOf(100 to 200,200 to 200)
        val shifts=listOf(.4f,0f)
        val segments=StitchGeometry.calculate(sizes,shifts,100f,horizontal=true)
        assertEquals(50f,segments[0].slotEnd,0.001f)
        assertEquals(50f,segments[1].slotStart,0.001f)
        assertEquals(50f,segments[0].visibleEnd,0.001f)
        assertEquals(50f,segments[1].visibleStart,0.001f)
        assertEquals(50f,segments[0].imageEnd-segments[0].imageStart,0.001f)
        assertEquals(40f,segments[0].visibleStart,0.001f)
    }

    @Test fun reverseSideSeamDragDoesNotOpenGapAtDivider() {
        val segments=StitchGeometry.calculate(listOf(100 to 200,200 to 200),listOf(0f,-.5f),100f,horizontal=true)
        assertEquals(50f,segments[0].visibleEnd,0.001f)
        assertEquals(50f,segments[1].visibleStart,0.001f)
        assertEquals(150f,segments[1].slotEnd,0.001f)
        assertEquals(100f,segments[1].visibleEnd,0.001f)
    }

    @Test fun dragCannotMoveAnImageCompletelyPastItsSeam() {
        val maxShift=(50f-2f)/100f
        val clamped=StitchGeometry.clampForSeamDrag(.45f,.5f,true,50f,100f,2f)
        assertEquals(maxShift,clamped,0.001f)
        val segment=StitchGeometry.calculate(listOf(100 to 200,200 to 200),listOf(clamped,0f),100f,true)[0]
        assertEquals(2f,segment.visibleEnd-segment.visibleStart,0.001f)
    }

    @Test fun lockedCropRangeExcludesOnlyBlankViewportEdges() {
        val segments=StitchGeometry.calculate(listOf(100 to 200,200 to 200),listOf(.2f,0f),100f,true)
        val range=StitchGeometry.visibleRange(segments,0f,150f)
        assertNotNull(range)
        assertEquals(20f,range!!.start,0.001f)
        assertEquals(150f,range.end,0.001f)

        assertEquals(-80f,StitchGeometry.clampEdgePan(true,0f,-120f,80f),0.001f)
        assertEquals(-68f,StitchGeometry.clampEdgePan(true,-80f,12f,80f),0.001f)
        assertEquals(80f,StitchGeometry.clampEdgePan(false,0f,120f,80f),0.001f)
        assertEquals(68f,StitchGeometry.clampEdgePan(false,80f,-12f,80f),0.001f)
    }
}
