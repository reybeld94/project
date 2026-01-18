package com.reybel.ellentv.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "ellentv_prefs"
        private const val KEY_UNIQUE_CODE = "unique_code"
    }

    /**
     * Save the unique user code
     */
    fun saveUniqueCode(code: String) {
        prefs.edit().putString(KEY_UNIQUE_CODE, code).apply()
    }

    /**
     * Get the saved unique code, or null if not set
     */
    fun getUniqueCode(): String? {
        return prefs.getString(KEY_UNIQUE_CODE, null)
    }

    /**
     * Check if the unique code has been set
     */
    fun hasUniqueCode(): Boolean {
        return getUniqueCode() != null
    }

    /**
     * Clear the unique code (for logout/reset)
     */
    fun clearUniqueCode() {
        prefs.edit().remove(KEY_UNIQUE_CODE).apply()
    }
}
