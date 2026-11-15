// Task 12: Multi-Language Support (Localisation)
//
// Language picker for English, isiZulu and Afrikaans. The selection is written to
// SharedPreferences through LocaleHelper and applied to the Locale configuration immediately,
// so the screen (and every screen behind it) redraws in the chosen language.

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.utils.LocaleHelper

class LanguageSelectionActivity : AppCompatActivity() {

    // Language currently highlighted in the list; only committed on "Save Changes".
    private var pendingLanguage: String = LocaleHelper.LANG_ENGLISH

    private lateinit var rowEnglish: View
    private lateinit var rowZulu: View
    private lateinit var rowAfrikaans: View
    private lateinit var radioEnglish: ImageView
    private lateinit var radioZulu: ImageView
    private lateinit var radioAfrikaans: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_setting_language_selection)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        pendingLanguage = LocaleHelper.getLanguage(this)
        renderSelection()
        wireListeners()
    }

    // -------------------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------------------
    private fun bindViews() {
        rowEnglish = findViewById(R.id.rowLanguageEnglish)
        rowZulu = findViewById(R.id.rowLanguageZulu)
        rowAfrikaans = findViewById(R.id.rowLanguageAfrikaans)
        radioEnglish = findViewById(R.id.radioLanguageEnglish)
        radioZulu = findViewById(R.id.radioLanguageZulu)
        radioAfrikaans = findViewById(R.id.radioLanguageAfrikaans)
    }

    private fun wireListeners() {
        findViewById<View>(R.id.btnLanguageBack).setOnClickListener { finish() }

        rowEnglish.setOnClickListener { select(LocaleHelper.LANG_ENGLISH) }
        rowZulu.setOnClickListener { select(LocaleHelper.LANG_ZULU) }
        rowAfrikaans.setOnClickListener { select(LocaleHelper.LANG_AFRIKAANS) }

        findViewById<TextView>(R.id.btnSaveLanguage).setOnClickListener { saveSelection() }
    }

    // -------------------------------------------------------------------------------------
    // Selection state
    // -------------------------------------------------------------------------------------
    private fun select(language: String) {
        pendingLanguage = language
        renderSelection()
    }

    // Draws a filled check on the active row and an empty ring on the others.
    private fun renderSelection() {
        radioEnglish.setImageResource(radioFor(LocaleHelper.LANG_ENGLISH))
        radioZulu.setImageResource(radioFor(LocaleHelper.LANG_ZULU))
        radioAfrikaans.setImageResource(radioFor(LocaleHelper.LANG_AFRIKAANS))

        rowEnglish.contentDescription = describe(LocaleHelper.LANG_ENGLISH)
        rowZulu.contentDescription = describe(LocaleHelper.LANG_ZULU)
        rowAfrikaans.contentDescription = describe(LocaleHelper.LANG_AFRIKAANS)
    }

    private fun radioFor(language: String): Int =
        if (language == pendingLanguage) R.drawable.ic_check_circle else R.drawable.ic_circle_outline

    private fun describe(language: String): String {
        val name = LocaleHelper.displayName(language)
        return if (language == pendingLanguage) {
            getString(R.string.language_option_selected_desc, name)
        } else {
            getString(R.string.language_option_desc, name)
        }
    }

    // -------------------------------------------------------------------------------------
    // Commit
    // -------------------------------------------------------------------------------------
    private fun saveSelection() {
        val current = LocaleHelper.getLanguage(this)
        if (current == pendingLanguage) {
            Toast.makeText(this, R.string.language_already_active, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Persist to SharedPreferences and update the Locale configuration.
        LocaleHelper.setLanguage(this, pendingLanguage)

        // Rebuild the stack from the dashboard so every cached view re-inflates in the new
        // language rather than only the screens created after this point.
        Toast.makeText(this, R.string.language_saved, Toast.LENGTH_SHORT).show()
        restartApp()
    }

    private fun restartApp() {
        val intent = Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}
