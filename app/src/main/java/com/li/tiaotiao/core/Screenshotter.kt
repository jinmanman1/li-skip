package com.li.tiaotiao.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

/**
 * 截图工具：API 30+ 的无障碍服务可以直接 takeScreenshot（不需要录屏权限）。
 * 用途：给「像素探针」规则校验颜色，给后续的模板匹配 / OCR 留接口。
 */
object Screenshotter {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var busy = false
        private set

    fun capture(service: AccessibilityService, cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb(null); return
        }
        if (busy) { cb(null); return }
        busy = true
        captureR(service, cb)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureR(service: AccessibilityService, cb: (Bitmap?) -> Unit) {
        runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (t: Throwable) {
                            null
                        } finally {
                            runCatching { result.hardwareBuffer.close() }
                            busy = false
                        }
                        cb(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        cb(null)
                    }
                })
        }.onFailure {
            busy = false
            cb(null)
        }
    }

    fun pixelMatches(bmp: Bitmap, probe: PixelProbe): Boolean {
        val x = (probe.x * (bmp.width - 1)).toInt().coerceIn(0, bmp.width - 1)
        val y = (probe.y * (bmp.height - 1)).toInt().coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(x, y)
        val dr = kotlin.math.abs(((c shr 16) and 0xFF) - ((probe.color shr 16) and 0xFF))
        val dg = kotlin.math.abs(((c shr 8) and 0xFF) - ((probe.color shr 8) and 0xFF))
        val db = kotlin.math.abs((c and 0xFF) - (probe.color and 0xFF))
        return dr <= probe.tolerance && dg <= probe.tolerance && db <= probe.tolerance
    }

    fun hexColor(c: Int): String = "#%06X".format(0xFFFFFF and c)
}
