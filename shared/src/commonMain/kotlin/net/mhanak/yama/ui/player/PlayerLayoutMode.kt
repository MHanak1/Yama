package net.mhanak.yama.ui.player

import androidx.compose.ui.unit.Dp

/**
 * How the full player arranges its artwork relative to the info + transport controls.
 *
 * The choice is resolved against the *player component's own* constraints (see [isHorizontal]), never
 * the screen/window size — the player may not own the whole screen (something could sit beside it), so
 * it adapts to the box it is actually given.
 */
enum class PlayerLayoutMode {
    /** Decide from the player's aspect ratio: horizontal once it is at least twice as wide as tall. */
    Auto,

    /** Always stack the artwork above the info + controls. */
    Vertical,

    /** Always place the artwork beside (left of) the info + controls. */
    Horizontal;

    /**
     * Whether to use the side-by-side arrangement for a player box of [width] × [height]. [Auto] flips
     * to horizontal at an aspect ratio of 2:1 or wider, so an ordinary 16:9 window still stacks.
     */
    fun isHorizontal(width: Dp, height: Dp): Boolean = when (this) {
        Auto -> width >= height * 2f
        Vertical -> false
        Horizontal -> true
    }
}
