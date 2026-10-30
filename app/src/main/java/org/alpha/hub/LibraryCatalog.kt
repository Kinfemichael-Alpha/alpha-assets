package org.alpha.hub

import android.content.Context
import org.json.JSONObject

/** መጻሕፍት / ፈተናዎች ዝርዝር ንጥል / one downloadable file */
data class LibEntry(val filename: String, val title: String, val url: String)
/** ቡድን (ምድብ) / a group (category) */
data class LibGroup(val group: String, val entries: List<LibEntry>)

/**
 * ከ assets (books.json / exams.json) ዝርዝሩን ያነባል እና ወደ UI ቅርጽ (ቡድን -> ፋይሎች) ያጠፍጣል።
 * Reads the nested catalog (see books.json / exams.json) and flattens it into UI groups.
 *
 * ደንብ: የፋይል ስም ከ URL አይወሰድም (kehulum እንዳይገባ) - ከንጹህ ርዕስ ይሰራል።
 * Rule: filenames are built from clean titles, never from URLs (so "kehulum" can never appear).
 */
object LibraryCatalog {
    private val badChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private fun safe(s: String) = s.replace(badChars, " ").replace(Regex("\\s+"), " ").trim().take(120)

    fun load(ctx: Context, kind: String): List<LibGroup> {
        val root = JSONObject(ctx.assets.open("$kind.json").bufferedReader(Charsets.UTF_8).readText())
        val groups = ArrayList<LibGroup>()
        val taken = HashSet<String>()
        fun unique(base: String): String {
            var n = base; var i = 2
            while (!taken.add(n.lowercase())) { n = "$base ($i)"; i++ }
            return "$n.pdf"
        }
        for (top in root.keys().asSequence()) {                  // Grade N | "2018 EC"
            val subjects = root.getJSONObject(top)
            for (subject in subjects.keys().asSequence()) {
                val node = subjects.get(subject)
                val entries = ArrayList<LibEntry>()
                if (node is org.json.JSONArray) {                  // books: [{name,url}]
                    for (i in 0 until node.length()) {
                        val o = node.getJSONObject(i)
                        val title = o.getString("name")
                        entries.add(LibEntry(unique(safe("$top - $subject - $title")), title, o.getString("url")))
                    }
                } else if (node is JSONObject) {                   // exams: {"Question paper": url}
                    for (label in node.keys().asSequence()) {
                        val title = if (label == "Open") subject else label
                        entries.add(LibEntry(unique(safe("$top - $subject - $label")), title, node.getString(label)))
                    }
                }
                if (entries.isNotEmpty()) groups.add(LibGroup("$top  |  $subject", entries))
            }
        }
        return groups
    }
}
