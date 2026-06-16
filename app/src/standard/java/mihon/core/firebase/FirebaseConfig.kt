package mihon.core.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object FirebaseConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // TachiyomiAT: Firebase auto-init (FirebaseInitProvider) should normally
        // have run by App.onCreate, but on some installs/ROMs the default
        // FirebaseApp isn't ready yet when we reach here — and calling
        // FirebaseCrashlytics.getInstance() then throws
        // "Default FirebaseApp is not initialized", crashing the app at startup
        // (FATAL EXCEPTION in App.onCreate). The previous order (analytics,
        // initializeApp, crashlytics) also initialized Crashlytics even when
        // FirebaseApp.initializeApp() had nothing to initialize against.
        //
        // Fix: explicitly initialize first, and only obtain Analytics/Crashlytics
        // instances if initialization succeeds. Wrap defensively so a Firebase
        // misconfiguration never blocks app startup — analytics/crashlytics are
        // non-essential.
        try {
            FirebaseApp.initializeApp(context)
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                analytics = FirebaseAnalytics.getInstance(context)
                crashlytics = FirebaseCrashlytics.getInstance()
            }
        } catch (e: Throwable) {
            // Firebase unavailable (missing/malformed google-services.json, no
            // network, restricted process). Degrade gracefully — the app still
            // runs, just without analytics/crash reporting.
            analytics = null
            crashlytics = null
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }
}
