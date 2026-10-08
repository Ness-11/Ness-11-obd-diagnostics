package pl.obd.readonly

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel

class ObdViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = (application as ObdApplication).engine
    val state = engine.state
    val logs = engine.logs
    fun setAlertSettings(config: pl.obd.core.AlertSettings) = engine.setAlertSettings(config)
    fun markIgnitionOff(off: Boolean) = engine.markIgnitionOff(off)
    fun viewReport(id: String) = engine.viewReport(id)
    fun compareRecordings() = engine.compareRecordings()
    fun closeReport() = engine.closeReport()
    fun exportBundle(id: String, uri: Uri) = engine.exportBundle(id, uri)
    fun setAutoDtcs(enabled: Boolean) = engine.setAutoDtcs(enabled)
    fun exportReport(id: String, uri: Uri) = engine.exportReport(id, uri)
    fun refreshDevices() = engine.refreshDevices()
    fun select(address: String) = engine.select(address)
    fun clearMessage() = engine.clearMessage()
    fun showMessage(message: String) = engine.showMessage(message)
    fun connect(demo: Boolean = false) = engine.connect(demo)
    fun disconnect() = engine.disconnect()
    fun pauseLive() = engine.pauseLive()
    fun startLive() = engine.startLive()
    fun toggleSignal(id: String, record: Boolean) = engine.toggleSignal(id, record)
    fun preset(ids: Set<String>) = engine.preset(ids)
    fun setCyclePause(ms: Long) = engine.setCyclePause(ms)
    fun setChartWindow(ms: Long) = engine.setChartWindow(ms)
    fun stopRecording() = engine.stopRecording()
    fun readVin() = engine.readVin()
    fun readDtcs() = engine.readDtcs()
    fun readSupported() = engine.readSupported()
    fun readReadiness() = engine.readReadiness()
    fun terminal(input: String) = engine.terminal(input)
    fun export(uri: Uri) = engine.exportLog(uri)
    fun exportRecording(id: String, uri: Uri) = engine.exportRecording(id, uri)
}
