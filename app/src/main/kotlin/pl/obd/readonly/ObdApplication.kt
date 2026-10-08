package pl.obd.readonly

import android.app.Application
class ObdApplication : Application() {
    val engine by lazy { DiagnosticEngine(this) }
}
