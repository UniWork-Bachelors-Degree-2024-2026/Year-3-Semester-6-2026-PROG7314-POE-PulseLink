package com.example.prog7314_poepart23

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class LocalisationResourcesTest {
    private val resources = File("src/main/res")
    private val placeholders = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")
    private data class Entry(val translatable: Boolean, val parts: Map<String, String>)
    private fun read(folder: String): Map<String, Entry> {
        val result = linkedMapOf<String, Entry>()
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        File(resources, folder).listFiles { file -> file.extension == "xml" }!!.forEach { file ->
            val root = factory.newDocumentBuilder().parse(file).documentElement
            for (i in 0 until root.childNodes.length) {
                val element = root.childNodes.item(i) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array")) continue
                val name = element.getAttribute("name")
                assertFalse("Duplicate $folder/$name", result.containsKey(name))
                val parts = linkedMapOf<String, String>()
                if (element.tagName == "string") parts["text"] = element.textContent
                else {
                    val items = element.getElementsByTagName("item")
                    for (index in 0 until items.length) {
                        val item = items.item(index) as Element
                        parts[if (element.tagName == "plurals") item.getAttribute("quantity") else index.toString()] = item.textContent
                    }
                }
                result[name] = Entry(element.getAttribute("translatable") != "false", parts)
            }
        }
        return result
    }
    @Test fun both_languages_cover_each_translatable_resource_with_matching_format_arguments() {
        val english = read("values")
        listOf("values-af", "values-zu").forEach { locale ->
            val translated = read(locale)
            english.filterValues { it.translatable }.forEach { (key, original) ->
                val local = translated[key]
                assertNotNull("Missing $locale/$key", local)
                original.parts.forEach { (part, text) ->
                    val translation = local!!.parts[part]
                    assertNotNull("Missing $locale/$key/$part", translation)
                    assertEquals("Format arguments $locale/$key/$part",
                        placeholders.findAll(text.replace("%%", "")).map { it.value }.sorted().toList(),
                        placeholders.findAll(translation!!.replace("%%", "")).map { it.value }.sorted().toList())
                }
            }
        }
    }
    @Test fun locale_files_cannot_override_configuration_or_non_translatable_branding() {
        val fixed = read("values").filterValues { !it.translatable }.keys + "default_web_client_id"
        listOf("values-af", "values-zu").forEach { locale ->
            assertTrue("Configuration overrides in $locale", read(locale).keys.intersect(fixed.toSet()).isEmpty())
        }
    }
}
