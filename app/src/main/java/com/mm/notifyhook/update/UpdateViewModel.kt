package com.mm.notifyhook.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mm.notifyhook.NotifyHookApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = (app as NotifyHookApp).webhookSettings

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** กล่องอัปเดตเปิดอยู่ไหม — ปิดได้ ("ภายหลัง") โดยยังจำสถานะไว้แสดงในหน้า Webhook */
    val dialogOpen = MutableStateFlow(false)

    /** เช็คเงียบๆ ตอนเปิดแอป ขึ้นกล่องเฉพาะเมื่อมีเวอร์ชันใหม่ ผิดพลาดก็ไม่รบกวน */
    fun autoCheck() {
        if (!AppUpdater.supported || autoChecked) return
        autoChecked = true
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            val result = withContext(Dispatchers.IO) { AppUpdater.check(settings) }
            _state.value = when (result) {
                is UpdateCheck.Available -> UpdateState.Available(result.release).also { dialogOpen.value = true }
                UpdateCheck.UpToDate -> UpdateState.UpToDate
                is UpdateCheck.Failed -> if (manual) UpdateState.Failed(result.message) else UpdateState.Idle
            }
        }
    }

    fun download() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> lastRelease ?: return
            else -> return
        }
        lastRelease = release
        dialogOpen.value = true
        _state.value = UpdateState.Downloading(release, 0f)
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            _state.value = try {
                val file = withContext(Dispatchers.IO) {
                    AppUpdater.download(getApplication(), settings, release) { p ->
                        _state.value = UpdateState.Downloading(release, p)
                    }
                }
                UpdateState.ReadyToInstall(release, file)
            } catch (e: IOException) {
                UpdateState.Failed(e.message ?: "โหลดไม่สำเร็จ")
            }
        }
    }

    fun dismiss() {
        dialogOpen.value = false
    }

    /** เวอร์ชันที่โหลดล่าสุด ไว้ให้กด "ลองใหม่" ได้หลังโหลดพลาด */
    private var lastRelease: AppRelease? = null

    private companion object {
        /** ต่อ process — หมุนจอ/สร้าง activity ใหม่ ไม่ต้องเช็คซ้ำ */
        var autoChecked = false
    }
}
