package com.pocketmempool.storage

import android.content.Context
import android.content.SharedPreferences

class ConnectionPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveConnection(
        host: String,
        port: Int,
        username: String,
        password: String,
        useSSL: Boolean
    ) {
        prefs.edit().apply {
            putString(KEY_HOST, host)
            putInt(KEY_PORT, port)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_USE_SSL, useSSL)
            putBoolean(KEY_CONFIGURED, true)
            apply()
        }
    }

    fun getHost(): String = prefs.getString(KEY_HOST, "localhost") ?: "localhost"
    
    fun getPort(): Int = prefs.getInt(KEY_PORT, 8332)
    
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    
    fun getUseSSL(): Boolean = prefs.getBoolean(KEY_USE_SSL, false)
    
    fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)
    
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "connection_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USE_SSL = "use_ssl"
        private const val KEY_CONFIGURED = "configured"
    }
}