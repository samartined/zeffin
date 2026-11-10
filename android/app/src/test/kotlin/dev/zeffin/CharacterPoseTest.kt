// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.ui.components.CharacterPose
import dev.zeffin.ui.components.Eye
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The character's key frames and timing, as the approved animation sheets give them (Character.md, "Animations"). */
class CharacterPoseTest {
    private val faces = Face.entries.flatMap { f -> if (f == Face.WORKING) (Scene.entries + null).map { f to it } else listOf(f to null) }

    @Test fun timing() {
        assertEquals(3500, CharacterPose.motion(Face.ACTIVE, null)?.periodMs)
        assertEquals(4000, CharacterPose.motion(Face.WORKING, null)?.periodMs)
        assertEquals(1200, CharacterPose.motion(Face.WRITING, null)?.periodMs)
        assertEquals(1400, CharacterPose.motion(Face.PERMISSION, null)?.periodMs)
        assertEquals(2400, CharacterPose.motion(Face.PAUSED, null)?.periodMs)
        assertEquals(1800, CharacterPose.motion(Face.CONNECTING, null)?.periodMs)
        assertEquals(listOf(1600, 1000, 1200, 2000, 2400), Scene.entries.map { CharacterPose.motion(Face.WORKING, it)?.periodMs })
        assertTrue("finished plays once", CharacterPose.motion(Face.FINISHED, null)!!.let { it.once && it.periodMs == 1120 })
        assertTrue("error plays once", CharacterPose.motion(Face.ERROR, null)!!.let { it.once && it.periodMs == 600 })
        assertNull("stopped stays still", CharacterPose.motion(Face.STOPPED, null))
        assertNull("offline stays still", CharacterPose.motion(Face.OFFLINE, null))
    }

    @Test fun everyFrameIsFinite() {
        for ((face, scene) in faces) {
            val period = CharacterPose.motion(face, scene)?.periodMs ?: continue
            for (ms in 0..period step 10) {
                val p = CharacterPose.at(face, scene, ms.toFloat())
                val values = listOf(p.dx, p.dy, p.squint, p.eyeScale, p.bodyDx, p.bodyDy, p.buttonScale) +
                    p.dots.orEmpty() + listOfNotNull(p.bar, p.folderBob) + p.zs.flatMap { listOf(it.x, it.y, it.alpha) }
                assertTrue("$face $scene at $ms ms", values.all { it.isFinite() })
                assertTrue("$face $scene at $ms ms: z's alpha", p.zs.all { it.alpha in 0f..1f })
            }
        }
    }

    @Test fun loopsComeBackToWhereTheyStarted() {
        val loops = faces.mapNotNull { (face, scene) -> CharacterPose.motion(face, scene)?.takeIf { !it.once }?.let { Triple(face, scene, it) } }
        for ((face, scene, m) in loops) {
            val start = CharacterPose.at(face, scene, 0f)
            val end = CharacterPose.at(face, scene, m.periodMs.toFloat())
            assertEquals("$face $scene dx", start.dx, end.dx, 0.01f)
            assertEquals("$face $scene squint", start.squint, end.squint, 0.01f)
            assertEquals("$face $scene bodyDy", start.bodyDy, end.bodyDy, 0.01f)
            assertEquals("$face $scene button", start.buttonScale, end.buttonScale, 0.01f)
        }
    }

    @Test fun oncePlaysEndAtTheStillFrame() {
        for (face in listOf(Face.FINISHED, Face.ERROR)) {
            val m = CharacterPose.motion(face, null)!!
            val end = CharacterPose.at(face, null, m.periodMs.toFloat())
            val still = CharacterPose.still(face)
            assertEquals(still.bodyDx, end.bodyDx, 0.01f)
            assertEquals(still.bodyDy, end.bodyDy, 0.01f)
        }
    }

    @Test fun keyFrames() {
        assertEquals("the blink closes", 0.08f, CharacterPose.at(Face.ACTIVE, null, 3400f).squint, 0.001f)
        assertEquals("the eye is open most of the time", 1f, CharacterPose.at(Face.ACTIVE, null, 2000f).squint, 0.001f)
        assertEquals("the hop's top", -3.2f, CharacterPose.at(Face.PERMISSION, null, 1260f).bodyDy, 0.001f)
        assertEquals("the bounce's top", -4.4f, CharacterPose.at(Face.FINISHED, null, 320f).bodyDy, 0.001f)
        assertEquals("the small hop", -1.8f, CharacterPose.at(Face.FINISHED, null, 880f).bodyDy, 0.001f)
        assertEquals("the shake's left", -2.4f, CharacterPose.at(Face.ERROR, null, 75f).bodyDx, 0.001f)
        assertEquals("the button goes in", 0.55f, CharacterPose.at(Face.WORKING, Scene.PRESS, 550f).buttonScale, 0.001f)
        assertEquals("the bar fills", 1f, CharacterPose.at(Face.WORKING, Scene.APP, 1800f).bar!!, 0.001f)
        assertTrue("the antenna's light, on", CharacterPose.at(Face.CONNECTING, null, 100f).antennaLit == true)
        assertFalse("then off", CharacterPose.at(Face.CONNECTING, null, 1000f).antennaLit == true)
        assertEquals("the first dot up", 1.6f, CharacterPose.at(Face.WRITING, null, 180f).dots!![0], 0.001f)
        assertEquals("the second waits", 0f, CharacterPose.at(Face.WRITING, null, 180f).dots!![1], 0.001f)
    }

    @Test fun stillFramesAreTheMockupsFrameZero() {
        assertEquals(Eye.ROUND, CharacterPose.still(Face.ACTIVE).eye)
        assertEquals(Eye.WIDE, CharacterPose.still(Face.PERMISSION).eye)
        assertEquals(2, CharacterPose.still(Face.PAUSED).zs.size)
        assertTrue(CharacterPose.still(Face.PAUSED).zs.all { it.alpha == 1f })
        assertEquals(Eye.HAPPY, CharacterPose.still(Face.FINISHED).eye)
        assertEquals(true, CharacterPose.still(Face.CONNECTING).antennaLit)
        assertTrue(CharacterPose.still(Face.OFFLINE).dim)
        assertEquals(3, CharacterPose.still(Face.WORKING, Scene.OTHER).bubbles)
    }
}
