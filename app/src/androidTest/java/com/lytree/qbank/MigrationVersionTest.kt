package com.lytree.qbank

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 库版本迁移：v12 只改了未交卷进度串的编码，表结构没动。
 *
 * 这里直接拿一份「表已经建好、版本停在 v11」的库跑真实的 [QBankDb.onUpgrade]，
 * 钉住 11 → 12 是**空迁移**：不建表、不删表、原有作答与设置都不动。
 */
@RunWith(AndroidJUnit4::class)
class MigrationVersionTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(ctx.cacheDir, "migration-11-12.db")

    @Before
    fun setUp() {
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun upgrade_11_to_12_is_empty_migration_and_keeps_data() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // v11 的表结构（v12 没有新增表，这里只建出会被迁移波及的那张）
            db.execSQL(
                "CREATE TABLE exam_progress(paper_id INTEGER PRIMARY KEY, answers TEXT, " +
                    "marked TEXT, elapsed_sec INTEGER, updated_at INTEGER)"
            )
            db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY, paper_id INTEGER)")
            db.execSQL(
                "INSERT INTO exam_progress(paper_id, answers, marked, elapsed_sec, updated_at) " +
                    "VALUES (1, '1,甲;乙;2,B,C', '1', 60, 0)"
            )
            db.execSQL("INSERT INTO records(id, paper_id) VALUES (9, 1)")
            // 模拟改动前落库的版本号
            db.execSQL("PRAGMA user_version = 11")
            val tablesBefore = tableNames(db)

            QBankDb.get(ctx).onUpgrade(db, 11, QBankDb.DB_VERSION)

            assertEquals("11 → 12 不应建表或删表", tablesBefore, tableNames(db))
            db.rawQuery(
                "SELECT answers, marked, elapsed_sec FROM exam_progress WHERE paper_id = 1",
                null
            ).use { c ->
                assertTrue("原有进度行不能丢", c.moveToFirst())
                assertEquals("旧格式进度串原样保留，由读取侧兼容", "1,甲;乙;2,B,C", c.getString(0))
                assertEquals("1", c.getString(1))
                assertEquals(60, c.getInt(2))
            }
            db.rawQuery("SELECT COUNT(*) FROM records", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("记录表不能被清空", 1, c.getInt(0))
            }
        }
    }

    @Test
    fun db_version_is_12() {
        assertEquals("v12 是这次进度串格式变更的版本标记", 12, QBankDb.DB_VERSION)
    }

    private fun tableNames(db: SQLiteDatabase): List<String> {
        val names = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null)
            .use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
        return names
    }
}
