package com.aibill.android.data.queue

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity

/**
 * 跨表写入器：**离线队列的入队口**，入账唯一的落库出口。
 *
 * 归属离线队列 module（架构候选 3）：入队要写 `pending_transactions` 并按形态联动
 * `notification_records`，因此它是本 module 的 data 层能力，**不**暴露成 domain 接口
 * （签名带 entity 的 domain 接口只能是一层转发，理由见 `domain/queue/SyncQueue.kt`）。
 *
 * ## 为什么要有这一层
 *
 * 一笔通知入账要写两张表：`pending_transactions`（队列行）与 `notification_records`
 * （审计行回填 `status` / `linked_client_id`）。两步必须原子，否则会出现
 * 「队列里有一笔账，但通知记录还停在 `parsed`」——用户再点一次确认就变成**重复账**。
 *
 * 这层抽象存在的**唯一**理由是 mockk 1.13.13 跑不动 `RoomDatabase.withTransaction { }`
 * 的 suspend lambda（没有接受 suspend lambda 的自定义 answer），历史实现因此把事务
 * 注释掉并留下 `// NOTE: P1`。与其继续注释「等 mockk 升级」，不如把事务收进可替换的
 * 写入器：生产走 [RoomRecordWriter]（Room 真事务），单测走 [DirectRecordWriter]（直写）。
 *
 * ⚠️ 因此 [DirectRecordWriter] **不提供任何原子性**。它只允许出现在单测里；
 * 生产构建的 Hilt 绑定见 `di/QueueModule`，指向的是 [RoomRecordWriter]。
 *
 * ## 三步写入有不变量，由 `DirectRecordWriterTest` 钉死
 *
 * 第三步 `updateStatus(confirmId, "confirmed", pending.clientId)` 丢不得：它把审计行
 * 移出 `observePending()` 的待确认集合。丢了 = 用户再点一次确认 = 重复账。
 * 且传入的 clientId **必须**是写进 `pending_transactions` 的那一个（两表关联键，
 * 撤销链路靠它定位队列行）。
 */
interface RecordWriter {

    /**
     * 原子写入一笔账，并按需联动通知审计记录。
     *
     * @return 新建的审计记录 id（[RecordWrite.newRecord] 非 null 时）；否则 null
     */
    suspend fun write(write: RecordWrite): Long?
}

/**
 * 一次跨表写入的内容。
 *
 * 两种通知入账形态在此汇合：
 * - AI 自动入账：[newRecord] 非 null、[confirmRecordId] 为 null
 *   ——先建审计行拿 id，再回填 `status` + `client_id`；
 * - 弹窗/通知中心确认：[newRecord] 为 null、[confirmRecordId] 非 null
 *   ——审计行早已存在，只回填；
 * - 外部静默入账 / 手工落离线：两者皆 null ——只写队列行。
 */
data class RecordWrite(
    val pending: PendingTransactionEntity,
    /** 需要**新建**的审计记录（AI 自动入账）。已存在时传 null。 */
    val newRecord: NotificationRecordEntity? = null,
    /** 需要标记 `confirmed` 并回填 `client_id` 的**既有**审计记录 id。 */
    val confirmRecordId: Long? = null,
)

/**
 * 两个 adapter 共用的三步写入。
 *
 * 步骤顺序与各调用点原来的写法逐行一致：
 * 建审计行 → 写队列行 → 回填审计行。抽出来是为了让两个 adapter 只差「是否包事务」，
 * 而不是各抄一遍三步。
 */
internal suspend fun applyRecordWrite(
    write: RecordWrite,
    pendingTransactionDao: PendingTransactionDao,
    notificationRecordDao: NotificationRecordDao,
): Long? {
    val insertedId = write.newRecord?.let { notificationRecordDao.insert(it) }
    pendingTransactionDao.insert(write.pending)
    // 没有既有记录就回填刚建的那条：AI 自动入账是「insert(status=confirmed) 再回填
    // linked_client_id」，拆成两步是为了保持与原实现逐行一致。
    val confirmId = write.confirmRecordId ?: insertedId
    if (confirmId != null) {
        notificationRecordDao.updateStatus(confirmId, "confirmed", write.pending.clientId)
    }
    return insertedId
}

/**
 * 生产 adapter：Room 真事务。
 *
 * [withTransaction] 需要 `room-ktx`（已在依赖里），它保证上面三步要么全成、要么全不成。
 */
class RoomRecordWriter(
    private val database: RoomDatabase,
    private val pendingTransactionDao: PendingTransactionDao,
    private val notificationRecordDao: NotificationRecordDao,
) : RecordWriter {

    override suspend fun write(write: RecordWrite): Long? = database.withTransaction {
        applyRecordWrite(write, pendingTransactionDao, notificationRecordDao)
    }
}

/**
 * 单测 adapter：直接写，不包事务。
 *
 * 存在的意义是让 [com.aibill.android.domain.record.RecordIntake] 与离线队列 module
 * 的全部行为
 * （字段装配、调用顺序、locale 不变）在纯 JVM 单测里可断言——而 mockk 1.13.13 无法
 * 让 `withTransaction` 的 suspend lambda 在测试里跑起来。
 *
 * ⚠️ **不提供原子性，生产路径禁止使用。**
 */
class DirectRecordWriter(
    private val pendingTransactionDao: PendingTransactionDao,
    private val notificationRecordDao: NotificationRecordDao,
) : RecordWriter {

    override suspend fun write(write: RecordWrite): Long? =
        applyRecordWrite(write, pendingTransactionDao, notificationRecordDao)
}
