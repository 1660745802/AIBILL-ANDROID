package com.aibill.android.data.notification

import java.io.File

/**
 * 测试夹具定位。
 *
 * 本项目其它测试统一用「相对路径 + `app/` 前缀回退」找 `src/test/resources` 下的夹具，
 * 因为测试工作目录可能是仓库根、也可能是 `app/`（取决于 Gradle 调用方式）。
 * 这里收口成一处，顺带提供 [moduleRoot]（写报告用），避免每个测试各写一遍。
 *
 * ⚠️ 不写死绝对路径：CI 上没有开发机的 Downloads 目录。
 */
internal object TestResources {

    fun file(name: String): File =
        listOf(File("src/test/resources/$name"), File("app/src/test/resources/$name"))
            .firstOrNull { it.exists() }
            ?: error("找不到测试夹具 $name（工作目录=${File(".").absolutePath}）")

    /** 夹具所在模块根（`app/`）：`<root>/src/test/resources` 上溯三层。 */
    fun moduleRoot(): File {
        val resources = listOf(File("src/test/resources"), File("app/src/test/resources"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到 src/test/resources（工作目录=${File(".").absolutePath}）")
        // absoluteFile：工作目录可能就是 `app/`，此时相对路径前三层会触底得到 null
        return checkNotNull(resources.absoluteFile.parentFile?.parentFile?.parentFile) {
            "无法从 ${resources.absolutePath} 上溯到模块根"
        }
    }
}
