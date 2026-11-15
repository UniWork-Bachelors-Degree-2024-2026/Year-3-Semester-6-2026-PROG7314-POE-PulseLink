// About, Legal & Medical Privacy portal: branding, authorship, statutory medical disclaimer
// and accordion-style Privacy Policy (POPIA) / Terms of Service sections with GitHub links.
// Reachable from both the Patient and Doctor settings screens.

package com.example.prog7314_poepart23

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import android.view.ViewGroup

class AboutActivity : AppCompatActivity() {

    private data class Section(val header: View, val body: View, val chevron: ImageView)

    private lateinit var sections: List<Section>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_about)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.btnAboutBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tvAboutVersion).text =
            getString(R.string.about_version_format, resolveVersionName())

        setUpAccordion()
        setUpLinks()
    }

    // Semantic version comes from build.gradle.kts (versionName = "3.0.0").
    private fun resolveVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: DEFAULT_VERSION
    } catch (e: Exception) {
        DEFAULT_VERSION
    }

    // ---------------------------------------------------------------------------------------
    // Accordion – only one legal section is open at a time
    // ---------------------------------------------------------------------------------------
    private fun setUpAccordion() {
        sections = listOf(
            Section(findViewById(R.id.headerPrivacy), findViewById(R.id.bodyPrivacy), findViewById(R.id.chevronPrivacy)),
            Section(findViewById(R.id.headerTerms), findViewById(R.id.bodyTerms), findViewById(R.id.chevronTerms))
        )
        sections.forEach { section ->
            section.header.setOnClickListener { toggle(section) }
        }
    }

    private fun toggle(target: Section) {
        val root = findViewById<ViewGroup>(R.id.aboutContent)
        TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(220))

        val willExpand = target.body.visibility != View.VISIBLE
        sections.forEach { section ->
            val expand = section === target && willExpand
            section.body.visibility = if (expand) View.VISIBLE else View.GONE
            section.chevron.animate().rotation(if (expand) 180f else 0f).setDuration(220).start()
            section.header.isSelected = expand
        }
    }

    // ---------------------------------------------------------------------------------------
    // GitHub links
    // ---------------------------------------------------------------------------------------
    private fun setUpLinks() {
        bindLink(R.id.btnGithubPrivacy, R.string.about_github_privacy_url)
        bindLink(R.id.btnGithubTerms, R.string.about_github_terms_url)
        bindLink(R.id.btnGithubRepo, R.string.about_github_repo_url)
    }

    private fun bindLink(viewId: Int, urlRes: Int) {
        findViewById<View>(viewId).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlRes))))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val DEFAULT_VERSION = "3.0.0"
    }
}
