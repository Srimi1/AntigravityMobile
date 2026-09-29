package app.antigravity.agent.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** When true, shell commands run without asking. Off by default. */
    var autoApproveShell: Boolean
        get() = prefs.getBoolean("auto_approve_shell", false)
        set(v) = prefs.edit().putBoolean("auto_approve_shell", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        val SUGGESTED_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")
    }
}
