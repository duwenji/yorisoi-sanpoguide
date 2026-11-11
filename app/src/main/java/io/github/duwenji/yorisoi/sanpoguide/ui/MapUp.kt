package io.github.duwenji.yorisoi.sanpoguide.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import kotlin.math.abs

/**
 * Which way is at the top of the main map: north, or the way the user faces (the compass, as
 * [io.github.duwenji.yorisoi.kit.geo.map.rememberHeading] gives it).
 */
object MapUp {
    /** The map turns only once the way faced is this far from the top, so a shaking hand doesn't keep turning it. */
    const val TURN_DEG = 10f

    /**
     * The bearing to put at the top, given the one at the top now ([current], null for north up)
     * and the way faced ([heading], null when unknown, which keeps the map as it is).
     */
    fun next(current: Float?, heading: Float?): Float? {
        if (heading == null) return current
        val faced = ((heading % 360f) + 360f) % 360f
        if (current == null) return faced
        val apart = abs(((faced - current) % 360f + 540f) % 360f - 180f)
        return if (apart >= MapUp.TURN_DEG) faced else current
    }
}

/** The bearing to put at the top of the map: null (north up) unless [headingUp], else from [heading] by [MapUp.next]. */
@Composable
fun rememberMapUp(headingUp: Boolean, heading: Float?): Float? {
    val held = remember { arrayOfNulls<Float>(1) }
    held[0] = if (headingUp) MapUp.next(held[0], heading) else null
    return held[0]
}

/**
 * Switches the map between north up and the way faced up; filled while the way faced is up, with
 * its arrow pointing north on the map turned by [up] (the map's own north arrow is under the facility bar).
 */
@Composable
fun MapUpButton(headingUp: Boolean, up: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (headingUp) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        if (headingUp) {
            Icon(
                Icons.Filled.Navigation,
                contentDescription = "向いている方向が上。矢印は北（押すと北が上）",
                modifier = Modifier.rotate(-(up ?: 0f)),
            )
        } else {
            Icon(Icons.Filled.Explore, contentDescription = "北が上（押すと向いている方向が上）")
        }
    }
}
