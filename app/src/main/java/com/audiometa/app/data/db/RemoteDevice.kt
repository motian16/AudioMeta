package com.audiometa.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * 一台被记住的 SPW 设备，以及它报出来过的所有地址。
 *
 * ## 为什么按"设备名"存
 *
 * 用户的要求是"根据设备名来存放，保证不产生孤立数据"。
 * 设备名（插件广播里的 `name`，默认「Salt Player for Windows」）
 * 是用户能认出来的标识 —— 比 IP 稳定，也比插件 ID 好认。
 *
 * ## 为什么不产生孤立数据
 *
 * 地址表用**外键**挂在设备行下面，并且建表时声明了
 * [ForeignKey.CASCADE]：删设备行时它的地址一起删。
 * 每次扫描发现某台设备报了新地址，都是"先清掉旧地址再写新的"，
 * 所以不会留下"这台设备早就没有了、地址还在库里"的垃圾。
 *
 * 另外 [RemoteDeviceDao.pruneExcept] 会在每轮扫描后把
 * **本轮没出现的设备**从库里删掉（连同地址）——
 * 这样换网络、换设备之后旧记录不会越积越多。
 */
@Entity(
    tableName = "remote_device",
    indices = [Index(value = ["deviceKey"], unique = true)]
)
data class RemoteDeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 去重用的稳定键。
     *
     * 用「插件 ID + 设备名」：同一台电脑换了 IP 还是它，
     * 重装插件（插件 ID 不变）也还是它。这样反复扫描不会产生重复行。
     */
    val deviceKey: String,
    /** 插件广播里的显示名。 */
    val name: String,
    val pluginId: String = "",
    val pluginVersion: String = "",
    /** WebSocket 端口。 */
    val wsPort: Int,
    /** UDP 发现端口，重扫时用。 */
    val udpPort: Int = 0,
    /** 最后一次见到这台设备的时间。 */
    val lastSeenAt: Long = System.currentTimeMillis()
)

/**
 * 一条可用地址。
 *
 * [kind] 沿用插件广播的分类：`lan` / `ipv6` / `pub`。
 * 连接时按"当前是 Wi-Fi 还是移动网络"挑（见 RemoteAddressPolicy）。
 */
@Entity(
    tableName = "remote_address",
    foreignKeys = [
        ForeignKey(
            entity = RemoteDeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("deviceId")]
)
data class RemoteAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** `lan` / `ipv6` / `pub`。 */
    val kind: String,
    val host: String,
    val port: Int,
    /** 写入时间；用于展示"这份地址是什么时候拿到的"。 */
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface RemoteDeviceDao {
    @Query("SELECT * FROM remote_device ORDER BY lastSeenAt DESC")
    suspend fun devices(): List<RemoteDeviceEntity>

    @Query("SELECT * FROM remote_device WHERE deviceKey = :key LIMIT 1")
    suspend fun deviceByKey(key: String): RemoteDeviceEntity?

    @Query("SELECT * FROM remote_address WHERE deviceId = :deviceId")
    suspend fun addressesOf(deviceId: Long): List<RemoteAddressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: RemoteDeviceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddresses(addresses: List<RemoteAddressEntity>)

    @Query("DELETE FROM remote_address WHERE deviceId = :deviceId")
    suspend fun clearAddresses(deviceId: Long)

    @Query("DELETE FROM remote_device WHERE lastSeenAt < :cutoff")
    suspend fun deleteDevicesOlderThan(cutoff: Long)

    /**
     * 一轮扫描的落库：写设备 + **整体替换**它的地址列表。
     *
     * 必须是事务：否则"清空了旧地址、还没写新的"这一瞬间进程若被杀，
     * 库里就剩一台没有任何地址的设备 —— 那正是要避免的孤立数据。
     */
    @Transaction
    suspend fun replaceDeviceWithAddresses(
        device: RemoteDeviceEntity,
        addresses: List<RemoteAddressEntity>
    ): Long {
        val id = upsertDevice(device)
        clearAddresses(id)
        if (addresses.isNotEmpty()) {
            insertAddresses(addresses.map { it.copy(deviceId = id) })
        }
        return id
    }

    /**
     * 清掉长期没出现的设备（连同地址，外键 CASCADE）。
     *
     * @param keepMillis 多久没见到就删。默认 30 天 —— 用户出差一个月回来
     *        还认得这台电脑，再久就没必要留着了。
     */
    @Transaction
    suspend fun pruneStale(keepMillis: Long = 30L * 24 * 3600 * 1000) {
        deleteDevicesOlderThan(System.currentTimeMillis() - keepMillis)
    }
}
