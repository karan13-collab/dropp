package com.dropp.dropp.core.security

import android.content.Context

object SecurityManager {
    var MASTER_KEY: String = ""

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("DroppSecurity", Context.MODE_PRIVATE)
        MASTER_KEY = prefs.getString("MasterKey", "") ?: ""
    }

    fun getKey(context: Context): String {
        if (MASTER_KEY.isEmpty()) {
            init(context)
        }
        return MASTER_KEY
    }

    fun saveKey(context: Context, key: String) {
        context.getSharedPreferences("DroppSecurity", Context.MODE_PRIVATE).edit().putString("MasterKey", key).apply()
        MASTER_KEY = key
    }
}