/**
 * PulseLink Legacy Main Activity Container
 *
 * Provides standard AppCompat host activity lifecycle bindings and edge-to-edge system insets (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. Android Developers. Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // Configure edge-to-edge system insets (Google Developers, 2024)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}