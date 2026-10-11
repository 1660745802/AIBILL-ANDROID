package com.aibill.android.data.repository

import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.dto.request.LoginRequest
import com.aibill.android.data.remote.dto.request.RegisterRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.AuthResponse
import com.aibill.android.data.remote.dto.response.UserDto
import com.aibill.android.data.remote.interceptor.SessionEndReason
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalDataOwnership
import com.aibill.android.domain.ownership.LocalOwnership
import com.aibill.android.service.NotificationProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 覆盖：
 * - P0#3：register 真接通 AuthApi.register（之前 RegisterScreen 直接跳登录，UI 调用 register 假接口）
 * - P1#44：换号时把本地数据交给归属 module 按「换号」处置
 * - **同账号重登不清本地数据**（数据丢失回归）：Token 过期 → 用户重登同一账号 →
 *   本地缓存与通知审计必须一张不动
 * - M1：saveSession 原子写入 token + userId + username + nickname
 * - C1：登录前经归属 module 做 SyncLock 握手（取消在飞的后台任务）
 *
 * 关键不变量：
 * 1. login 成功且**换号/首次**（旧归属为 null 或 != 新 userId）→ 处置意图必须是
 *    [DisposalIntent.SwitchingAccount]（由它清 4 张表）
 * 2. login 成功且**同账号** → 处置意图必须是 [DisposalIntent.SameAccountRelogin]，
 *    **不得**出现 [DisposalIntent.SwitchingAccount]，且据处置结果重放 401 待补偿通知
 * 3. register 成功 → 必处置 SwitchingAccount（新账号，无条件）
 * 4. login/register 失败 → 不处置、不调 saveSession
 * 5. 换号路径下处置顺序在 saveSession 之前（避免 user B 拿到 user A 的脏数据）
 * 6. login/register 前必调 `awaitSyncQuiescence()`（SyncLock 握手）
 * 7. **B6**：logout 不得删除 `last_known_user_id` —— 否则「登出→登回同账号」
 *    退化成「首次登录」并清空本地数据，违反登出弹窗对用户的承诺。
 *    但保留它也不得削弱换号防护（见下面两个 B6 用例）
 *
 * ## 架构候选 4 之后：这里断言的是**意图**，表级不变量搬去了 `LocalDataOwnershipImplTest`
 *
 * 收口前本类直连 4 张表（DAO + 同步队列），断言是「某张表被 deleteAll 了」。
 * 现在处置归 [LocalDataOwnership]，本类只负责**选对意图**，于是断言换成
 * 「dispose 收到哪个意图」+「据处置结果做了什么」。
 *
 * 表清单（哪几张、一起清）由 `LocalDataOwnershipImplTest` 用**真实 Room 按表名**钉住——
 * 那才是清单唯一的落点；收口前两份清单各由本类与 `ServerConfigViewModelTest` 钉，
 * 「清单本身」反而没人钉。两侧用例都在、语义等价：
 * `DATA LOSS regression` / `logout then SAME|DIFFERENT account login`（本类）
 * ＋ 同名的 4 表保留用例（归属 module）。
 */
class AuthRepositoryImplTest {

    private val authApi: AuthApi = mockk()
    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val ownership: LocalDataOwnership = mockk()
    private val notificationProcessor: NotificationProcessor = mockk(relaxed = true)

    private fun makeUserDto(): UserDto = UserDto(
        id = 42,
        username = "alice",
        nickname = "Alice",
        role = "user",
    )

    private fun makeAuthResponse(token: String = "jwt-xyz"): AuthResponse = AuthResponse(
        token = token,
        user = makeUserDto(),
    )

    private fun newRepo(): AuthRepositoryImpl = AuthRepositoryImpl(
        authApi = authApi,
        tokenManager = tokenManager,
        userPreferences = userPreferences,
        localDataOwnership = ownership,
        notificationProcessor = notificationProcessor,
    )

    /**
     * 打桩归属 module 三件套：SyncLock 握手、归属判据（`last_known_user_id`）、处置结果。
     *
     * [lastAccount] = 上一次会话归属的账号；`null` = 从未登录过 / 老版本没写该字段。
     */
    private fun stubOwnership(
        lastAccount: Int? = null,
        outcome: DisposalOutcome = DisposalOutcome.Cleared,
    ) {
        coEvery { ownership.awaitSyncQuiescence() } just runs
        every { ownership.currentOwnership() } returns LocalOwnership(accountId = lastAccount)
        coEvery { ownership.dispose(any()) } returns outcome
    }

    @Test
    fun `account switch - previous account differs - dispose as SwitchingAccount then saveSession with all 4 fields`() = runTest {
        // 旧会话是 user 7，新登录拿到 user 42 → 换号
        stubOwnership(7)
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        val result = newRepo().login("alice", "pass")

        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        val user = (result as com.aibill.android.domain.model.Result.Success).data
        assertEquals(42, user.id)
        assertEquals("alice", user.username)

        // C1：login 前必做 SyncLock 握手（取消在飞的后台任务 + 等锁）
        coVerify(exactly = 1) { ownership.awaitSyncQuiescence() }

        // P1#44：换号 → 处置意图必须是 SwitchingAccount（它清 4 张表）
        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SameAccountRelogin) }

        // 换号后那批数据已属别人，不重放它的 401 待补偿通知
        coVerify(exactly = 0) { notificationProcessor.replayAuthPending() }

        // M1：tokenManager.saveSession 收到 4 字段
        coVerifyOrder {
            tokenManager.saveSession(
                token = "jwt-xyz",
                userId = 42,
                username = "alice",
                nickname = "Alice",
            )
        }
    }

    @Test
    fun `DATA LOSS regression - same account re-login disposes as SameAccountRelogin and never as SwitchingAccount`() = runTest {
        // 场景：Token 30 天过期 → 队列里 3 条离线记录被标 failed("Token expired")
        //      → 用户重新登录【同一个账号】(userId 42 → 42)
        // 原实现无条件 clearLocalCache() → 队列表 deleteAll
        // 用户「断网时记的账」被物理删除，不可恢复。
        stubOwnership(42, DisposalOutcome.Retained)
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        val result = newRepo().login("alice", "pass")

        assertTrue(result is com.aibill.android.domain.model.Result.Success)

        // 核心断言：同号重登只能走「保留」意图，绝不能落到换号那条（那会清 4 张表）
        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SameAccountRelogin) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SwitchingServer) }
        // 401 卡住的记录被归属 module 复活回 pending 并主动排了同步；
        // 判据配对（ERROR_TOKEN_EXPIRED ↔ ERROR_TOKEN_EXPIRED_PATTERN）与 4 表保留
        // 由 LocalDataOwnershipImplTest 用真实 Room 钉死。
        // 数据确实留下了 → 重放因 401 未解析的通知（B2）
        coVerify(exactly = 1) { notificationProcessor.replayAuthPending() }
        // session 照常刷新
        coVerify(exactly = 1) {
            tokenManager.saveSession(
                token = "jwt-xyz", userId = 42, username = "alice", nickname = "Alice",
            )
        }
    }

    @Test
    fun `unknown previous userId (legacy session) - conservatively dispose as SwitchingAccount`() = runTest {
        // 拿不到旧归属（老版本没写 session 字段）→ 无法判断是否换号 → 保守清理，
        // 保持 PR #44 的跨账号防护
        stubOwnership(null)
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().login("alice", "pass")

        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SameAccountRelogin) }
    }

    @Test
    fun `login error - no disposal, no saveSession, return Error`() = runTest {
        stubOwnership(42)
        coEvery { authApi.login(LoginRequest("alice", "wrongpass")) } returns ApiResponse(
            code = 401, data = null, message = "Unauthorized"
        )

        val result = newRepo().login("alice", "wrongpass")

        assertTrue(result is com.aibill.android.domain.model.Result.Error)
        // 关键：错误时不动本地数据（用户输错密码不至于丢离线交易）
        coVerify(exactly = 0) { ownership.dispose(any()) }
        coVerify(exactly = 0) { notificationProcessor.replayAuthPending() }
        coVerify(exactly = 0) { tokenManager.saveSession(any(), any(), any(), any()) }
    }

    @Test
    fun `register success - P0#3 fake-register fix, real AuthApi call`() = runTest {
        stubOwnership()
        coEvery { authApi.register(RegisterRequest("bob", "pass", "INV-1", "Bob")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(token = "jwt-new"), message = "ok"
        )

        val result = newRepo().register("bob", "pass", "INV-1", "Bob")

        // P0#3 fix：register 真的调用了 AuthApi.register
        coVerify(exactly = 1) { authApi.register(RegisterRequest("bob", "pass", "INV-1", "Bob")) }
        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        // 注册一定是新账号 → 无条件按换号处置
        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        // C1：注册前同样握手
        coVerify(exactly = 1) { ownership.awaitSyncQuiescence() }
        coVerify(exactly = 1) {
            tokenManager.saveSession(
                token = "jwt-new",
                userId = 42,
                username = "alice",
                nickname = "Alice",
            )
        }
    }

    @Test
    fun `disposal happens BEFORE saveSession on account switch - prevents user A data leak`() = runTest {
        // PR C1/P1#44：换号时处置本地数据必须在 saveSession 之前，
        // 否则在处置和 saveSession 之间，后台可能用旧 token 写新表
        stubOwnership(7)
        coEvery { authApi.login(LoginRequest("a", "b")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().login("a", "b")

        // 验证顺序：先处置，再写 session
        coVerifyOrder {
            ownership.dispose(DisposalIntent.SwitchingAccount)
            tokenManager.saveSession(any(), any(), any(), any())
        }
    }

    @Test
    fun `sync quiescence happens BEFORE the login request`() = runTest {
        stubOwnership(42, DisposalOutcome.Retained)
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().login("alice", "pass")

        coVerifyOrder {
            ownership.awaitSyncQuiescence()
            ownership.currentOwnership()
            authApi.login(LoginRequest("alice", "pass"))
        }
    }

    @Test
    fun `logout - endSession SIGNED_OUT + clearUserInfo, does NOT wipe all preferences`() = runTest {
        newRepo().logout()

        // 只结束会话：token / userId / username / nickname 清掉，
        // last_known_user_id 保留（它是本地缓存的归属判据）——由 TokenManager.endSession 保证。
        coVerify(exactly = 1) { tokenManager.endSession(SessionEndReason.SIGNED_OUT) }
        // 用户偏好只清身份三项，**不**清 serverUrl / 主题 / 通知隐私等设备设置
        coVerify(exactly = 1) { userPreferences.clearUserInfo() }
        // 登出**不**处置本地数据（那句承诺「本地未同步的数据不会丢失」靠的就是这里）
        coVerify(exactly = 0) { ownership.dispose(any()) }
    }

    // ==================== B6：登出不得成为数据丢失的入口 ====================

    /**
     * B6 回归：登出后再登回**同一账号**，离线记账必须保留。
     *
     * 历史 bug：`logout()` 调 `clearSessionAndIdentity()` 把 `last_known_user_id` 一起删了，
     * 于是下次登录归属判据为 null → 判成换号 → 物理删除用户离线记的账，
     * 而登出弹窗向用户承诺过「本地未同步的数据不会丢失」。
     */
    @Test
    fun `logout then SAME account login - data retained, nothing disposed as account switch`() = runTest {
        // 登出保留了 last_known_user_id（last_known_user_id = 42）
        stubOwnership(42, DisposalOutcome.Retained)
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().logout()
        val result = newRepo().login("alice", "pass")

        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        // 核心：走「保留」意图，一个字节都不会少
        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SameAccountRelogin) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        // 401 卡住的行被复活 → 数据留下了 → 重放待补偿通知
        coVerify(exactly = 1) { notificationProcessor.replayAuthPending() }
    }

    /**
     * B6 的另一半：**保留身份不等于放弃换号防护**。
     * 登出后换成另一个账号，仍然必须按换号处置（清本地数据）。
     */
    @Test
    fun `logout then DIFFERENT account login - still disposes as SwitchingAccount`() = runTest {
        // 登出保留了 last_known_user_id = 7，新登录拿到 42 → 换号
        stubOwnership(7)
        coEvery { authApi.login(LoginRequest("bob", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().logout()
        val result = newRepo().login("bob", "pass")

        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        // 跨账号防护不得因为保留 last_known_user_id 而削弱
        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SameAccountRelogin) }
        coVerify(exactly = 0) { notificationProcessor.replayAuthPending() }
    }
}
