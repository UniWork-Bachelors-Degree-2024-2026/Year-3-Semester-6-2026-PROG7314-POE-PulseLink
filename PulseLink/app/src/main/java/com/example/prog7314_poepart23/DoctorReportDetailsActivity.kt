/*
 * Doctor diagnostic report inspection view displaying clinical metadata,
 * download actions, and sign-off verification.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import android.widget.Toast
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.comingSoonOnClick
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.toastOnClick
import com.example.prog7314_poepart23.utils.wireBack

class DoctorReportDetailsActivity : AppCompatActivity() { // (Google Developers, 2024)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_report_details)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        wireControls()
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)
        comingSoonOnClick(R.id.btnShare)

        toastOnClick(R.id.btnFlagReport, R.string.action_report_flagged)
        toastOnClick(R.id.btnDownloadPdf, R.string.action_download_started)

        onClick(R.id.btnSignOffReport) {
            Toast.makeText(this, R.string.action_report_signed_off, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
