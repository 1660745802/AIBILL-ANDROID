package com.aibill.android.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpdateVersionCompareTest {

    @Test
    fun `patch版本更新`() {
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "1.0.0"))
    }

    @Test
    fun `minor版本更新`() {
        assertTrue(UpdateManager.isNewerVersion("1.1.0", "1.0.5"))
    }

    @Test
    fun `major版本更新`() {
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun `相同版本不更新`() {
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun `旧版本不更新`() {
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun `位数不同 1_0比1_0_0相等`() {
        assertFalse(UpdateManager.isNewerVersion("1.0", "1.0.0"))
    }

    @Test
    fun `位数不同 1_0_1比1_0更新`() {
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "1.0"))
    }

    @Test
    fun `非数字段容错`() {
        // 异常格式不崩，按0处理
        assertFalse(UpdateManager.isNewerVersion("abc", "1.0.0"))
    }
}
