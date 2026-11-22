package me.riverfrozer.penguinsmagic

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉、导航图与 AppBarConfiguration 三处的一致性。
 *
 * 这三处靠 id 字符串对上，编译器不检查：抽屉里多一个条目而导航图里没有对应目的地时，
 * 装上去照样能点，只是点了没反应或者当场抛 IllegalArgumentException。新增页面必须同时改三处。
 */
class NavGraphTest {

    private val graph = read("res/navigation/nav_graph.xml")
    private val menu = read("res/menu/nav_menu.xml")
    private val activity = read("java/me/riverfrozer/penguinsmagic/MainActivity.kt")

    private val destinations = Regex("""<fragment\b[^>]*android:id="@\+id/(\w+)"""")
        .findAll(graph).map { it.groupValues[1] }.toSet()

    private val menuIds = Regex("""android:id="@\+id/(\w+)"""")
        .findAll(menu).map { it.groupValues[1] }.toList()

    @Test
    fun `every drawer entry has a destination in the graph`() {
        assertTrue("抽屉条目不能为空", menuIds.isNotEmpty())
        menuIds.forEach {
            assertTrue("抽屉条目 $it 在导航图里没有对应目的地", it in destinations)
        }
    }

    @Test
    fun `every drawer entry is a top level destination`() {
        val topLevel = activity.substringAfter("AppBarConfiguration(").substringBefore("binding.drawerLayout")
        menuIds.forEach {
            assertTrue("$it 没进 AppBarConfiguration，抽屉图标会变成返回箭头", "R.id.$it" in topLevel)
        }
    }

    @Test
    fun `the rules page is reachable from the drawer`() {
        assertTrue("本机规则页必须独占一个抽屉条目", "blockRulesFragment" in menuIds)
        assertTrue("editBlockRuleFragment" in destinations)
        assertTrue("action_rules_to_edit" in graph)
    }

    @Test
    fun `the whitelist page is reachable from the drawer`() {
        assertTrue("白名单页必须独占一个抽屉条目", "whitelistFragment" in menuIds)
        assertTrue("editWhitelistFragment" in destinations)
        assertTrue("action_whitelist_to_edit" in graph)
    }

    @Test
    fun `every destination points at a fragment class that exists`() {
        Regex("""android:name="(me\.riverfrozer[\w.]+)"""").findAll(graph).forEach {
            val path = "java/" + it.groupValues[1].replace('.', '/') + ".kt"
            assertTrue("导航图指向的类不存在：$path", File(module(), "src/main/$path").isFile)
        }
    }

    @Test
    fun `every action and the start destination resolve`() {
        Regex("""(?:app:destination|app:startDestination)="@id/(\w+)"""").findAll(graph).forEach {
            assertTrue("@id/${it.groupValues[1]} 不是导航图里的目的地", it.groupValues[1] in destinations)
        }
    }

    private fun read(relative: String): String = File(module(), "src/main/$relative").readText()

    private fun module(): File =
        listOf(File("."), File("app")).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("找不到模块目录，测试的工作目录不对：${File(".").absolutePath}")
}