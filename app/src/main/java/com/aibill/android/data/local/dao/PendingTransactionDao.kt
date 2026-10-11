package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aibill.android.data.local.entity.PendingTransactionEntity

/**
 * `pending_transactions` = **已确认记录的本地缓存**。
 *
 * 写入只有一条路（`data/record/RecordWriter`，且它只在服务端确认之后才调 [insert]），
 * 所以这张表里不会出现「已接受但还没送达」的行。
 *
 * ## `sync_status` / `retry_count` / `last_error` 三列已废弃（**列保留，不写 migration**）
 *
 * 它们属于「待上传队列」语义：`pending`（排队等上传）、`failed`（本地终态）、
 * `retry_count`（重试次数）、`last_error`（失败原因）。这套语义随离线队列一起取消，
 * **本 DAO 不再写它们**，也不再有任何查询按它们过滤。
 *
 * 唯一还用到 `sync_status` 的地方是**区分历史遗留行**：老版本写下的 `pending` / `failed`
 * 行不参与任何判定（既不当缓存、也不被撤销删），只有 `synced` 才是「已确认缓存」的标记。
 * 新写入的行恒为 `synced`。之所以不清掉那些行：清表要么写 migration（本工程禁止），
 * 要么在启动时做一次破坏性删除，两者的代价都大于收益——它们从此无人读取。
 */
@Dao
interface PendingTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PendingTransactionEntity>)

    @Query("DELETE FROM pending_transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM pending_transactions WHERE client_id = :clientId LIMIT 1")
    suspend fun findByClientId(clientId: String): PendingTransactionEntity?

    @Query("DELETE FROM pending_transactions WHERE client_id = :clientId")
    suspend fun deleteByClientId(clientId: String)
}
