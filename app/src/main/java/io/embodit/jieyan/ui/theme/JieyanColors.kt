package io.embodit.jieyan.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design-doc §3 "Visual tokens"): chalk on slate. The expert's yellow
 * chalk, the worker's blue chalk, the live red dot, the expert console's slate.
 *
 * This is the only file with colour literals. The four semantic roles are injected into `PicoTheme`
 * by [JieyanTheme]; the brand tokens are used directly (chalk lines on the glass, ink on chalk yellow).
 */
object JieyanColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: permission denied / PIN wrong / picture unavailable
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: reconnecting / stale / paused
    val Passable = Color(0xFF7CF2A3) // design-style: fixed-figma-color passable: connected / granted
    val Interaction = Color(0xFFFFE14D) // design-style: fixed-figma-color interaction: chalk yellow, selected keys and the primary button

    // ---- brand / decorative tokens outside ColorScheme
    val ChalkYellow = Color(0xFFFFE14D) // design-style: fixed-figma-color chalkYellow: the expert's chalk and pointer
    val ChalkWhite = Color(0xFFF6F2E6) // design-style: fixed-figma-color chalkWhite: expert chalk 2
    val ChalkPink = Color(0xFFFF8FB1) // design-style: fixed-figma-color chalkPink: expert chalk 3
    val ChalkBlue = Color(0xFF8CD5FF) // design-style: fixed-figma-color chalkBlue: the worker's chalk; the glass frame while the pen is on
    val InkOnChalk = Color(0xFF1E1A0E) // design-style: fixed-figma-color inkOnChalk: icons and text on chalk yellow
    val LiveRed = Color(0xFFFF3B30) // design-style: fixed-figma-color liveRed: sharing dot
    val Slate = Color(0xFF20262A) // design-style: fixed-figma-color slate: expert console background
    val SlateDeep = Color(0xFF161B1E) // design-style: fixed-figma-color slateDeep: viewfinder letterbox
    val StrokeHalo = Color(0x73000000) // design-style: fixed-figma-color strokeHalo black at 45 %: outline of every chalk line
    val GlassVeil = Color(0x0AFFFFFF) // design-style: fixed-figma-color white at 4 %: the veil of the glass while the pen is on (ECS quad)
    val ChipInk = Color(0xC720262A) // design-style: fixed-figma-color slate at 78 %: the pen chip on the glass (same as the expert page's chips)

    /** The expert's three chalks by wire name (the page sends hex). */
    fun chalk(hex: String): Color =
        runCatching {
            val h = hex.trim().removePrefix("#")
            val rgb = h.toLong(16)
            if (h.length == 6) Color((0xFF000000L or rgb).toInt()) else Color(rgb.toInt())
        }.getOrDefault(ChalkYellow)
}
