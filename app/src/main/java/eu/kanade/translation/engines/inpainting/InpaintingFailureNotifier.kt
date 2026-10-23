package eu.kanade.translation.engines.inpainting

import android.app.Application
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Failed pages still translate — on the original image — so the degraded
 * output must reach the user instead of living only in logcat. At most one
 * toast per cooldown: a whole chapter of failing pages neither spams the
 * reader nor goes silent again after the first missed toast.
 */
internal object InpaintingFailureNotifier {
    internal const val FAILURE_COOLDOWN_MS = 5 * 60_000L

    private val latch = InpaintingFailureLatch(cooldownMs = FAILURE_COOLDOWN_MS)

    // Lazy so merely loading this object stays safe on the JVM (Android
    // stubs throw on Looper access); tests never announce through here.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun announce(modeName: String) {
        if (!latch.tryAnnounce()) return
        logcat(LogPriority.WARN) { "Inpainting failure surfaced to the user (mode=$modeName)" }
        mainHandler.post {
            val context = Injekt.get<Application>()
            context.toast(context.stringResource(ATMR.strings.translation_inpaint_failed_toast, modeName))
        }
    }
}

/** Cooldown latch; the clock is injectable so tests can drive it. */
internal class InpaintingFailureLatch(private val cooldownMs: Long) {
    private var lastAnnounceAtMs = 0L
    private var announcedOnce = false

    @Synchronized
    fun tryAnnounce(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (announcedOnce && nowMs - lastAnnounceAtMs < cooldownMs) return false
        announcedOnce = true
        lastAnnounceAtMs = nowMs
        return true
    }
}
