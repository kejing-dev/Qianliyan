package io.embodit.jieyan

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.jieyan.ui.session.JieyanScreen
import io.embodit.jieyan.ui.theme.JieyanTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0) holds
 * the whole product: lobby card, call strip and the head-locked visor glass. There is no WindowContainer.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            JieyanTheme {
                JieyanScreen()
            }
        }
    }
