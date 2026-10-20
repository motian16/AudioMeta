package com.audiometa.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.audiometa.app.util.AppLog

@Database(
    entities = [
        TrackEntity::class,
        FolderEntity::class,
        ArtistEntity::class,
        PluginRecordEntity::class,
        SourceRecordEntity::class,
        ScrapeLogEntity::class,
        LyricsCacheEntity::class,
        MetaTagEntity::class,
        SilenceProfileEntity::class,
        RemoteDeviceEntity::class,
        RemoteAddressEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun folderDao(): FolderDao
    abstract fun artistDao(): ArtistDao
    abstract fun pluginDao(): PluginDao
    abstract fun sourceDao(): SourceDao
    abstract fun scrapeLogDao(): ScrapeLogDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun remoteDeviceDao(): RemoteDeviceDao
    abstract fun metaTagDao(): MetaTagDao
    abstract fun silenceProfileDao(): SilenceProfileDao

    companion object {
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "audiometa.db")
                /*
                 * 允许主线程查询。**这是兜底，不是给主线程开绿灯。**
                 *
                 * Room 对每一个 DAO 方法（包括 suspend 的）都会先做一次
                 * `assertNotMainThread()`，命中就抛 IllegalStateException。
                 * 应用里还有几处老代码绕不开：SilenceProfileStore 用
                 * `runBlocking` 读静音档案（它的调用点遍布播放、详情页、
                 * 缓存页），那是个同步 API，主线程上调一次就是进程级崩溃。
                 *
                 * 拿掉这个断言之后，最坏情况是主线程卡一下（ANR 级别），
                 * 而不是"用着用着突然闪退"。真正的主线程入库调用应该在调用点
                 * 挪到 IO 线程上（`rescanMetaTags` 就是这么修的），
                 * 这里只是保证漏网的那几处不再把应用带走。
                 */
                .allowMainThreadQueries()

                /*
                 * 迁移：**只加表，绝不动已有的表。**
                 *
                 * 这里踩过一次大坑：之前只写了 `fallbackToDestructiveMigration()`，
                 * 而每次为了加两张新表就把 version 加一 —— Room 找不到迁移，
                 * 就把**整个数据库删掉重建**。用户看到的是"更新一次，歌全没了，
                 * 得重新扫描一遍"，连续两个版本都这样。
                 *
                 * 现在凡是新增表/索引，都必须在这里写一条 Migration，
                 * 而且只用 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`。
                 * [MIGRATIONS] 是空的时候就别动 version。
                 */
                .addMigrations(*MIGRATIONS)

                /*
                 * 兜底：只有在**没有对应迁移**且版本又对不上时才会走到这里。
                 *
                 * 保留它是因为"宁可重建也不能打不开"；正常情况下永远不该触发，
                 * 一旦触发会在日志里明确写出来（见下面的 onDestructiveMigration）。
                 */
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        AppLog.e(
                            TAG,
                            "数据库缺少迁移，已重建：歌曲索引被清空。" +
                                "音频文件不受影响，到「设置 - 音乐库」重新扫描即可恢复。"
                        )
                    }
                })
                .fallbackToDestructiveMigration()

                /*
                 * 版本号**变小**时的兜底。
                 *
                 * Room 默认不允许降级：已装的库是 version 6、新包装的是 version 5 时，
                 * 它会直接抛 IllegalStateException —— **应用一打开就闪退**，
                 * 而且用户完全不知道为什么。
                 *
                 * 什么时候会遇到：开发者回退了版本号、或者用户在两个分支之间来回装。
                 * 这种情况没有"正确的迁移"可言（未来的表结构我们并不知道），
                 * 只能重建；但**至少要让它能打开**，而不是变成一个打不开的应用。
                 */
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        /**
         * 历史版本 → 当前版本的迁移链。
         *
         * 每条迁移都**只做增量**（建新表、建新索引），不触碰任何已有数据。
         * 加新迁移时的规矩：
         *   1. 先写迁移，再把 @Database 的 version 加一；
         *   2. 新表一律 `CREATE TABLE IF NOT EXISTS`，和实体定义**逐字对齐**
         *      （列名、类型、NOT NULL、默认值都要一致，否则 Room 校验不过）。
         */
        private val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            /**
             * 4 → 5：新增遥控设备与地址两张表。
             *
             * 这两张表是纯新增的缓存（记住过的 SPW 设备地址），
             * 老用户升上来是空的，第一次扫描时自动填。
             */
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `remote_device` (
                            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            `deviceKey` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `pluginId` TEXT NOT NULL,
                            `pluginVersion` TEXT NOT NULL,
                            `wsPort` INTEGER NOT NULL,
                            `udpPort` INTEGER NOT NULL,
                            `lastSeenAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_remote_device_deviceKey` ON `remote_device` (`deviceKey`)"
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `remote_address` (
                            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            `deviceId` INTEGER NOT NULL,
                            `kind` TEXT NOT NULL,
                            `host` TEXT NOT NULL,
                            `port` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            FOREIGN KEY(`deviceId`) REFERENCES `remote_device`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_remote_address_deviceId` ON `remote_address` (`deviceId`)"
                    )
                }
            }
        )

        private const val TAG = "AppDatabase"
    }
}