package com.nosmai.quickstart.webrtc

import android.content.Context
import java.io.FileNotFoundException
import java.util.Properties

internal class DemoConfig(context: Context) {
    private val values = Properties().apply {
        try {
            context.assets.open("nosmai.properties").bufferedReader().use { load(it) }
        } catch (_: FileNotFoundException) {
            // The role picker explains which settings are required.
        }
    }

    val nosmaiLicenseKey = values.getProperty("nosmaiLicenseKey", "").trim()
    val roomName = values.getProperty("roomName", "nosmai-live").trim()

    fun problem(isHost: Boolean): String? = when {
        isHost && nosmaiLicenseKey.isBlank() -> "Set nosmaiLicenseKey in nosmai.properties for the host."
        roomName.isBlank() -> "Set roomName in nosmai.properties."
        else -> null
    }
}
