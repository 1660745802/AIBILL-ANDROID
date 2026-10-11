package com.aibill.android.presentation.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aibill.android.domain.model.SyncQueueStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 横幅**渲染层**的文案与按钮不变量。
 *
 * ## 为什么必须测渲染（而不只测 `bannerVariantOf`）
 *
 * 档位映射只能证明「(0,3) → Failed」，证明不了「Failed 档真的没有按钮」。
 * 而本切片的核心承诺恰好是：**failed 行没有任何处置入口，所以不许给按钮**——
 * 给了按钮就是新的谎报（点了「同步」永远不会捞起 failed 行，
 * 因为 `PendingTransactionDao.getAllPending()` 只捞 `sync_status='pending'`）。
 * 这条不变量只存在于 composable 的 `when` 里，只有行为级测试能锁住它。
 *
 * 同时逐字锁住四档文案：文案是硬约束的产物（不得出现用户没有 UI 入口执行的动作），
 * 有人顺手加一句「去流水页手动删除」时，本测试会红。
 *
 * 与 `SyncBannerVariantTest`（纯 JVM）分家的原因：那是 JUnit5 Jupiter，
 * 这里是 JUnit4 + Robolectric（`createComposeRule` 需要 Android 环境）。
 * **JUnit4/5 钩子不能混在一个类里**（项目跑 useJUnitPlatform()，
 * 混用会让 @Before/@BeforeEach 静默失效），故独立成文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PendingSyncBannerRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setBanner(status: SyncQueueStatus, isSyncing: Boolean = false) {
        composeTestRule.setContent {
            PendingSyncBanner(
                status = status,
                isSyncing = isSyncing,
                onSyncClick = {},
            )
        }
        composeTestRule.waitForIdle()
    }

    // ---------- 档 A：Pending ----------

    @Test
    fun `pending banner keeps the original copy and the sync button`() {
        setBanner(SyncQueueStatus(pending = 3, failed = 0))

        composeTestRule.onNodeWithText("3 笔还没同步").assertExists()
        composeTestRule.onNodeWithText("联网后会自动补上，也可以现在手动同步").assertExists()
        composeTestRule.onNodeWithText("同步").assertExists()

        // 档 A 不得出现失败档的措辞
        composeTestRule.onNodeWithText("重试待上传").assertDoesNotExist()
        composeTestRule.onNodeWithText("3 笔同步失败").assertDoesNotExist()
    }

    @Test
    fun `pending banner shows 同步中 while syncing`() {
        setBanner(SyncQueueStatus(pending = 3, failed = 0), isSyncing = true)

        composeTestRule.onNodeWithText("同步中").assertExists()
        composeTestRule.onNodeWithText("同步").assertDoesNotExist()
    }

    // ---------- 档 B：Failed ----------

    /**
     * 本切片最要紧的一条：failed-only **不得有任何按钮**。
     *
     * 变异防线：把 composable 里的 `Failed -> Unit` 改成渲染 `AppTextButton`，
     * 本用例立刻变红。
     */
    @Test
    fun `failed banner offers no action because there is no UI path to act on`() {
        setBanner(SyncQueueStatus(pending = 0, failed = 3))

        composeTestRule.onNodeWithText("3 笔同步失败").assertExists()
        composeTestRule.onNodeWithText("已停止自动重试").assertExists()

        // 无按钮：两个按钮文案都不得存在
        composeTestRule.onNodeWithText("同步").assertDoesNotExist()
        composeTestRule.onNodeWithText("重试待上传").assertDoesNotExist()
        composeTestRule.onNodeWithText("同步中").assertDoesNotExist()
    }

    /**
     * Failed 档的禁词表：**既不能指路，也不能做承诺**。
     *
     * - 前五个：指不到 UI 入口的动作指引（presentation 层没有本地明细展示，
     *   也没有逐行删除入口；`deleteByClientId` 只被 SyncWorker 补偿与通知撤销调用）。
     * - 后四个：**正向承诺**。Failed 档不得主张任何「会 / 稍后 / 自动」性质的
     *   上传或重试结局——它既不知道服务端有没有这笔（见 `PendingSyncBanner` KDoc
     *   引用的 `AutoRecordActionReceiver` 真相表），也不能保证会再试。
     *
     * ⚠️ 不能拿裸词「自动重试」当禁词：获批文案正是「已停止自动重试」，
     * 那样会把合法文案自己锁死。必须用「会自动重试」这种**承诺式**子串来区分
     * 「已停止 X」（状态陈述）与「会 X」（承诺）。
     */
    private val forbiddenFailedCopy = listOf(
        // 指不到入口的动作指引
        "流水页",
        "手动删除",
        "重新登录",
        "清空",
        "去处理",
        // 正向承诺（不得主张上传状态 / 会自动重试）
        "会自动补上",
        "会自动重试",
        "稍后",
        "会自动上传",
    )

    /** 文案硬约束：不得出现上述任何禁词。文案语义的真伪由 SyncWorker / DAO 层测试保证，本文件只锁呈现。 */
    @Test
    fun `failed banner copy must not point at actions the user cannot perform`() {
        setBanner(SyncQueueStatus(pending = 0, failed = 2))

        forbiddenFailedCopy.forEach { forbidden ->
            composeTestRule
                .onNodeWithText(forbidden, substring = true)
                .assertDoesNotExist()
        }
    }

    /**
     * 禁词表**自身的**有效性自检：获批文案必须通过，典型谎报必须被逮住。
     * 否则上一条测试可能只是因为禁词表被删空而永远绿。
     */
    @Test
    fun `forbidden list passes the approved copy but catches promise-style lies`() {
        val approved = "已停止自动重试"
        assertFalse(
            "获批文案不得命中任何禁词：$approved",
            forbiddenFailedCopy.any { approved.contains(it) },
        )

        listOf("稍后会自动重试", "会自动补上").forEach { lie ->
            assertTrue(
                "谎报文案必须被禁词逮住：$lie",
                forbiddenFailedCopy.any { lie.contains(it) },
            )
        }
    }

    // ---------- 档 B'：Mixed ----------

    @Test
    fun `mixed banner shows both counts and scopes the button to pending only`() {
        setBanner(SyncQueueStatus(pending = 2, failed = 1))

        composeTestRule.onNodeWithText("2 笔待上传 · 1 笔同步失败").assertExists()
        composeTestRule.onNodeWithText("同步失败的不会自动重试").assertExists()
        composeTestRule.onNodeWithText("重试待上传").assertExists()

        // 作用域被按钮文案限定：裸「同步」不出现，避免暗示 failed 也会被处理
        composeTestRule.onNodeWithText("同步").assertDoesNotExist()
        // 档 A 的承诺句（"会自动补上"）绝不能出现在含 failed 的档里
        composeTestRule
            .onNodeWithText("联网后会自动补上", substring = true)
            .assertDoesNotExist()
    }

    // ---------- Hidden ----------

    @Test
    fun `empty snapshot renders nothing at all`() {
        setBanner(SyncQueueStatus(pending = 0, failed = 0))

        listOf(
            "笔还没同步",
            "笔同步失败",
            "同步",
            "重试待上传",
        ).forEach { text ->
            composeTestRule
                .onNodeWithText(text, substring = true)
                .assertDoesNotExist()
        }
    }
}
