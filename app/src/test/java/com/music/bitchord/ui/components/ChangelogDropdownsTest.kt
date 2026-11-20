package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogDropdownsTest {
    @Test
    fun aChangelogSplitsAtItsModuleHeadingsAndKeepsWhatComesBeforeThem() {
        val parsed = parseChangelog("Intro line.\n\n## Player\n\n- **A.** one\n- **B.** two\n\n## Library\n\n- **C.** three\n")
        assertEquals("Intro line.", parsed.intro)
        assertEquals(listOf("Player", "Library"), parsed.sections.map { it.title })
        assertEquals(listOf(2, 1), parsed.sections.map { it.entries })
        assertTrue(parsed.sections[0].body.startsWith("- **A.**"))
    }

    @Test
    fun everyReleaseIsGroupedByTheAppsModulesInTheirOrder() {
        val en = listOf("Player", "Home", "Library", "Import & Transfer", "Auden DJ", "Account", "Settings", "Look & Glass", "Connect", "Updates", "Fixes")
        val es = listOf("Reproductor", "Inicio", "Biblioteca", "Importar y transferir", "Auden DJ", "Cuenta", "Ajustes", "Imagen y cristal", "Connect", "Actualizaciones", "Correcciones")
        val root = File("../changelog").takeIf { it.isDirectory } ?: File("changelog")
        val versions = root.listFiles { f -> f.isDirectory }.orEmpty()
        assertTrue("no changelog folders found", versions.isNotEmpty())
        for (dir in versions) {
            for ((file, order) in listOf("en.md" to en, "es.md" to es)) {
                val titles = parseChangelog(File(dir, file).readText()).sections.map { it.title }
                assertTrue("${dir.name}/$file has no sections", titles.isNotEmpty())
                assertTrue("${dir.name}/$file has a heading that is not a module: $titles", titles.all { it in order })
                assertEquals("${dir.name}/$file is out of order", titles.sortedBy { order.indexOf(it) }, titles)
                assertEquals("${dir.name}/$file repeats a module", titles.distinct(), titles)
            }
        }
    }
}
