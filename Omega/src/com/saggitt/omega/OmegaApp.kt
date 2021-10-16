/*
 *  This file is part of Omega Launcher
 *  Copyright (c) 2021   Saul Henriquez
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.saggitt.omega

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.android.launcher3.Utilities
import com.android.quickstep.RecentsActivity

class OmegaApp : Application() {
    val TAG = "OmegaApp"
    var mismatchedQuickstepTarget = false
    private val recentsEnabled by lazy { checkRecentsComponent() }
    var accessibilityService: OmegaAccessibilityService? = null

    fun performGlobalAction(action: Int): Boolean {
        return if (accessibilityService != null) {
            accessibilityService!!.performGlobalAction(action)
        } else {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            false
        }
    }

    private fun checkRecentsComponent(): Boolean {
        if (!Utilities.ATLEAST_R) {
            Log.d(TAG, "API < P, disabling recents")
            return false
        }

        val resId = resources.getIdentifier("config_recentsComponentName", "string", "android")
        if (resId == 0) {
            Log.d(TAG, "config_recentsComponentName not found, disabling recents")
            return false
        }
        val recentsComponent = ComponentName.unflattenFromString(resources.getString(resId))
        if (recentsComponent == null) {
            Log.d(TAG, "config_recentsComponentName is empty, disabling recents")
            return false
        }
        val isRecentsComponent = recentsComponent.packageName == packageName
                && recentsComponent.className == RecentsActivity::class.java.name
        if (!isRecentsComponent) {
            Log.d(
                TAG,
                "config_recentsComponentName ($recentsComponent) is not Lawnchair, disabling recents"
            )
            return false
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            Log.d(TAG, "Quickstep target doesn't match, disabling recents")
            mismatchedQuickstepTarget = true
            return false
        }
        return true
    }
    companion object {
        @JvmStatic
        var instance: OmegaApp? = null
            private set

        @JvmStatic
        val isRecentsEnabled: Boolean
            get() = instance?.recentsEnabled == true
    }
}
val Context.omegaApp get() = applicationContext as OmegaApp