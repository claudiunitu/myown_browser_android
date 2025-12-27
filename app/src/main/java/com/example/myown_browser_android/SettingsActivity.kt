package com.example.myown_browser_android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.GeolocationPermissions
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var adHostsInput: TextInputEditText
    private lateinit var adHostsLayout: TextInputLayout
    private lateinit var saveButton: Button
    private lateinit var updateUrlInput: TextInputEditText
    private lateinit var updateButton: Button
    private lateinit var adBlockerSwitch: SwitchMaterial
    private lateinit var homePageInput: TextInputEditText
    private lateinit var thirdPartyCookieSwitch: SwitchMaterial
    private lateinit var cameraPermissionSwitch: SwitchMaterial
    private lateinit var micPermissionSwitch: SwitchMaterial
    private lateinit var locationPermissionSwitch: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        adHostsInput = findViewById(R.id.ad_hosts_input)
        adHostsLayout = findViewById(R.id.ad_hosts_layout)
        saveButton = findViewById(R.id.save_button)
        updateUrlInput = findViewById(R.id.update_url_input)
        updateButton = findViewById(R.id.update_button)
        adBlockerSwitch = findViewById(R.id.ad_blocker_switch)
        homePageInput = findViewById(R.id.home_page_input)
        thirdPartyCookieSwitch = findViewById(R.id.third_party_cookie_switch)
        cameraPermissionSwitch = findViewById(R.id.camera_permission_switch)
        micPermissionSwitch = findViewById(R.id.mic_permission_switch)
        locationPermissionSwitch = findViewById(R.id.location_permission_switch)

        val sharedPrefs = getSharedPreferences("AdBlockerPrefs", Context.MODE_PRIVATE)

        // Load saved data
        val adHosts = sharedPrefs.getStringSet("ad_hosts", null) ?: readDefaultAdHosts()
        val updateUrl = sharedPrefs.getString("update_url", "")
        val isAdBlockerEnabled = sharedPrefs.getBoolean("ad_blocker_enabled", true)
        val homePage = sharedPrefs.getString("home_page_url", "https://www.google.com")
        val blockThirdPartyCookies = sharedPrefs.getBoolean("block_third_party_cookies", true)
        val allowCamera = sharedPrefs.getBoolean("allow_camera", false)
        val allowMic = sharedPrefs.getBoolean("allow_mic", false)
        val allowLocation = sharedPrefs.getBoolean("allow_location", false)

        adHostsInput.setText(adHosts.joinToString("\n"))
        updateUrlInput.setText(updateUrl)
        adBlockerSwitch.isChecked = isAdBlockerEnabled
        homePageInput.setText(homePage)
        thirdPartyCookieSwitch.isChecked = blockThirdPartyCookies
        cameraPermissionSwitch.isChecked = allowCamera
        micPermissionSwitch.isChecked = allowMic
        locationPermissionSwitch.isChecked = allowLocation

        // Set initial state of the views
        updateViews(isAdBlockerEnabled)

        locationPermissionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                GeolocationPermissions.getInstance().clearAll()
            }
        }

        adBlockerSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateViews(isChecked)
        }

        saveButton.setOnClickListener {
            val newHosts = adHostsInput.text.toString().lines().filter { it.isNotBlank() }.toSet()
            val newUrl = updateUrlInput.text.toString()
            val adBlockerEnabled = adBlockerSwitch.isChecked
            val newHomePage = homePageInput.text.toString()
            val blockThirdPartyCookiesValue = thirdPartyCookieSwitch.isChecked
            val allowCameraValue = cameraPermissionSwitch.isChecked
            val allowMicValue = micPermissionSwitch.isChecked
            val allowLocationValue = locationPermissionSwitch.isChecked

            sharedPrefs.edit {
                putBoolean("ad_blocker_enabled", adBlockerEnabled)
                putStringSet("ad_hosts", newHosts)
                putString("update_url", newUrl)
                putString("home_page_url", newHomePage)
                putBoolean("block_third_party_cookies", blockThirdPartyCookiesValue)
                putBoolean("allow_camera", allowCameraValue)
                putBoolean("allow_mic", allowMicValue)
                putBoolean("allow_location", allowLocationValue)
            }

            // Add a flag to indicate that a reload is needed
            val resultIntent = Intent()
            resultIntent.putExtra("settings_changed", true)
            setResult(RESULT_OK, resultIntent)

            finish() // Close the settings screen.
        }

        updateButton.setOnClickListener {
            val url = updateUrlInput.text.toString()
            if (url.isNotBlank()) {
                updateHostList(url)
            }
        }
    }

    private fun updateViews(isEnabled: Boolean) {
        adHostsLayout.isEnabled = isEnabled
        updateUrlInput.isEnabled = isEnabled
        updateButton.isEnabled = isEnabled
    }

    private fun updateHostList(urlString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val onlineHosts = URL(urlString).readText().lines().filter { it.isNotBlank() }.toSet()

                withContext(Dispatchers.Main) {
                    val sharedPrefs = getSharedPreferences("AdBlockerPrefs", Context.MODE_PRIVATE)
                    val currentHosts = sharedPrefs.getStringSet("ad_hosts", setOf()) ?: setOf()
                    val updatedHosts = currentHosts + onlineHosts // Union of the sets, no duplicates

                    adHostsInput.setText(updatedHosts.joinToString("\n"))

                    sharedPrefs.edit {
                        putStringSet("ad_hosts", updatedHosts)
                    }

                    Toast.makeText(this@SettingsActivity, "Host list updated!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Error updating list: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("SettingsActivity", "Error updating host list", e)
            }
        }
    }

    private fun readDefaultAdHosts(): Set<String> {
        return try {
            val inputStream: InputStream = resources.openRawResource(R.raw.ad_hosts)
            inputStream.bufferedReader().use { it.readLines() }.toSet()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error reading default ad hosts", e)
            setOf()
        }
    }
}
