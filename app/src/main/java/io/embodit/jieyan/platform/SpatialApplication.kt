package io.embodit.jieyan.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.jieyan.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
