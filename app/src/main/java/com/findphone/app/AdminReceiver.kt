package com.findphone.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(c: Context, i: Intent) { Store(c).adminActive = true }
    override fun onDisabled(c: Context, i: Intent) { Store(c).adminActive = false }
}
