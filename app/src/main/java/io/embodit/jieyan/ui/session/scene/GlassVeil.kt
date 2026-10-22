package io.embodit.jieyan.ui.session.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.jieyan.domain.Vec3
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.ui.theme.JieyanColors

/**
 * The 4 % white veil of the visor glass while the worker's pen is on (design-doc §3 "视野玻璃").
 *
 * It is an ECS quad and not a fill of the glass panel, so that the glass panel stays what the design
 * says it is: no material, nothing over its whole area (QA round 1, q1-1: with the pen on, the glass
 * came up with an opaque black band; the cause was the pen chip's backdrop material, see `PenChip`,
 * and the full-area fill was taken out of the panel at the same time). The quad is TRANSPARENT with
 * depth write off (the recipe of Spatial Ruler's see-through materials): it never hides what lies behind.
 *
 * It hangs under the glass slot's holder, 3 mm behind the panel, so it is head-locked, scaled to the
 * camera intrinsics and hidden together with the glass.
 */
internal class GlassVeil(holder: Entity, widthM: Float, heightM: Float) {
    private val entity: Entity? =
        runCatching {
            val material =
                UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                    // a transparent unlit material multiplies base-colour alpha and opacity: alpha 1, the share in the opacity
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    setOpacity(JieyanColors.GlassVeil.alpha)
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                    setDepthWrite(false)
                }
            ModelEntity(MeshResource.createBox(Vector3(widthM, heightM, THICKNESS_M), 0f), material).also {
                runCatching { it.setName("glassVeil") }
                it.setLocal(position = Vec3(0f, 0f, -BEHIND_M))
                holder.addChild(it)
                it.components[ModelComponent::class.java]?.isVisible = false
            }
        }.onFailure { JieyanLog.e("SCENE glass veil failed: $it", it) }.getOrNull()

    private var shown = false

    fun show(on: Boolean) {
        if (on == shown) return
        shown = on
        entity?.components?.get(ModelComponent::class.java)?.isVisible = on
        JieyanLog.i("SCENE glass veil ${if (on) "on" else "off"}")
    }

    companion object {
        private const val THICKNESS_M = 0.0004f
        private const val BEHIND_M = 0.003f
    }
}
