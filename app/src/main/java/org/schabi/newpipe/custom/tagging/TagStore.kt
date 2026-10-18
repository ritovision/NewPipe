/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.schabi.newpipe.custom.aicontrol.AiCommandReceipt

/** Independently versioned metadata. No NewPipe Room table or migration is changed. */
class TagStore(context: Context, name: String? = FILE_NAME) : SQLiteOpenHelper(context, name, null, 2) {
    companion object {
        const val FILE_NAME = "ritovision-tags.db"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE category (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE tag (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, category_id INTEGER REFERENCES category(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX tag_category ON tag(category_id)")
        db.execSQL("CREATE TABLE assignment (service_id INTEGER NOT NULL, url TEXT NOT NULL, tag_id INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE, PRIMARY KEY(service_id,url,tag_id))")
        db.execSQL("CREATE INDEX assignment_tag ON assignment(tag_id)")
        db.execSQL("CREATE TABLE stream_revision (service_id INTEGER NOT NULL, url TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(service_id,url))")
        db.execSQL("CREATE TABLE removal_intent (service_id INTEGER NOT NULL, url TEXT NOT NULL, library_id INTEGER NOT NULL, PRIMARY KEY(service_id,url))")
        db.execSQL("CREATE TABLE catalog_meta (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL)")
        db.execSQL("INSERT INTO catalog_meta VALUES(1,0)")
        createAiTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported tag database upgrade; existing data was not modified" }
        createAiTables(db)
    }

    internal fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = action(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun changed(db: SQLiteDatabase) = db.execSQL("UPDATE catalog_meta SET revision=revision+1 WHERE id=1")

    fun version(): Long = readableDatabase.rawQuery("SELECT revision FROM catalog_meta WHERE id=1", null).use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    fun definitions(): TagDefinitions {
        val categories = readableDatabase.rawQuery("SELECT id,name FROM category ORDER BY name_key,id", null).use { c ->
            buildList { while (c.moveToNext()) add(TagCategory(c.getLong(0), c.getString(1))) }
        }
        val tags = readableDatabase.rawQuery("SELECT id,name,category_id FROM tag ORDER BY name_key,id", null).use { c ->
            buildList { while (c.moveToNext()) add(TagDefinition(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))) }
        }
        return TagDefinitions(categories, tags, version())
    }

    fun revision(key: StreamKey): Long = readableDatabase.rawQuery(
        "SELECT revision FROM stream_revision WHERE service_id=? AND url=?",
        key.args()
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun bumpRevision(db: SQLiteDatabase, key: StreamKey) {
        db.execSQL("INSERT OR IGNORE INTO stream_revision VALUES(?,?,0)", arrayOf<Any>(key.serviceId, key.url))
        db.execSQL("UPDATE stream_revision SET revision=revision+1 WHERE service_id=? AND url=?", arrayOf<Any>(key.serviceId, key.url))
    }

    fun assigned(key: StreamKey): Set<Long> = readableDatabase.rawQuery(
        "SELECT tag_id FROM assignment WHERE service_id=? AND url=? ORDER BY tag_id",
        key.args()
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    fun allAssignments(): Map<StreamKey, Set<Long>> {
        val result = LinkedHashMap<StreamKey, MutableSet<Long>>()
        readableDatabase.rawQuery("SELECT service_id,url,tag_id FROM assignment", null).use { c ->
            while (c.moveToNext()) result.getOrPut(StreamKey(c.getInt(0), c.getString(1))) { LinkedHashSet() }.add(c.getLong(2))
        }
        return result
    }

    fun editSnapshot(key: StreamKey): TagEditSnapshot = TagEditSnapshot(definitions(), assigned(key), revision(key))

    fun verifyEdit(key: StreamKey, expectedRevision: Long, tagIds: Set<Long>) {
        check(revision(key) == expectedRevision) { "This video's tags changed. Close and reopen the editor before applying." }
        val known = definitions().tags.mapTo(HashSet()) { it.id }
        check(known.containsAll(tagIds)) { "A selected tag was deleted. Close and reopen the editor." }
    }

    fun setTags(key: StreamKey, tagIds: Set<Long>, expectedRevision: Long) = transaction { db ->
        verifyEdit(key, expectedRevision, tagIds)
        db.delete("assignment", "service_id=? AND url=?", key.args())
        for (tag in tagIds) {
            db.insertOrThrow(
                "assignment",
                null,
                ContentValues().apply {
                    put("service_id", key.serviceId)
                    put("url", key.url)
                    put("tag_id", tag)
                }
            )
        }
        bumpRevision(db, key)
        changed(db)
    }

    fun createCategory(name: String): Long = transaction { db ->
        val result = db.insertOrThrow("category", null, nameValues(name))
        changed(db)
        result
    }

    fun createTag(name: String, categoryId: Long?): Long = transaction { db ->
        val values = nameValues(name)
        if (categoryId == null) values.putNull("category_id") else values.put("category_id", categoryId)
        val result = db.insertOrThrow("tag", null, values)
        changed(db)
        result
    }

    fun renameCategory(id: Long, name: String, expectedRevision: Long? = null) = transaction { db ->
        if (expectedRevision != null && expectedRevision != version()) throw TagDefinitionConflict(definitions())
        check(db.update("category", nameValues(name), "id=?", arrayOf(id.toString())) == 1) { "Category no longer exists" }
        changed(db)
    }

    fun editTag(id: Long, name: String, categoryId: Long?, expectedRevision: Long? = null) = transaction { db ->
        if (expectedRevision != null && expectedRevision != version()) throw TagDefinitionConflict(definitions())
        val values = nameValues(name)
        if (categoryId == null) values.putNull("category_id") else values.put("category_id", categoryId)
        check(db.update("tag", values, "id=?", arrayOf(id.toString())) == 1) { "Tag no longer exists" }
        changed(db)
    }

    fun deletionPlan(id: Long, category: Boolean): TagDeletePlan {
        val tags = if (category) definitions().tags.filter { it.categoryId == id }.map { it.id } else listOf(id)
        var assignments = 0
        for (tag in tags) {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM assignment WHERE tag_id=?", arrayOf(tag.toString())).use {
                check(it.moveToFirst())
                assignments += it.getInt(0)
            }
        }
        return TagDeletePlan(id, category, tags.size, assignments, version())
    }

    fun delete(plan: TagDeletePlan) = transaction { db ->
        check(version() == plan.version) { "Tags changed while the confirmation was open. Review the deletion again." }
        val sql = if (plan.category) {
            "SELECT DISTINCT a.service_id,a.url FROM assignment a JOIN tag t ON t.id=a.tag_id WHERE t.category_id=?"
        } else {
            "SELECT service_id,url FROM assignment WHERE tag_id=?"
        }
        val affected = db.rawQuery(sql, arrayOf(plan.id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(StreamKey(c.getInt(0), c.getString(1))) }
        }
        affected.forEach { bumpRevision(db, it) }
        check(db.delete(if (plan.category) "category" else "tag", "id=?", arrayOf(plan.id.toString())) == 1) { "The tag or category no longer exists" }
        changed(db)
    }

    /** Durable before the NewPipe delete. The caller must hold TagRuntime's mutation lock. */
    fun beginRemoval(key: StreamKey, libraryId: Long) = transaction { db ->
        db.insertOrThrow(
            "removal_intent",
            null,
            ContentValues().apply {
                put("service_id", key.serviceId)
                put("url", key.url)
                put("library_id", libraryId)
            }
        )
    }

    fun beginRemovals(keys: List<StreamKey>, libraryId: Long) = transaction { db ->
        for (key in keys) {
            db.insertOrThrow(
                "removal_intent",
                null,
                ContentValues().apply {
                    put("service_id", key.serviceId)
                    put("url", key.url)
                    put("library_id", libraryId)
                }
            )
        }
    }

    fun finishRemovals(keys: List<StreamKey>) = transaction { db ->
        for (key in keys) {
            db.delete("assignment", "service_id=? AND url=?", key.args())
            bumpRevision(db, key)
            db.delete("removal_intent", "service_id=? AND url=?", key.args())
        }
        changed(db)
    }

    fun pendingRemovals(): List<TagRemovalIntent> = readableDatabase.rawQuery(
        "SELECT service_id,url,library_id FROM removal_intent",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(TagRemovalIntent(StreamKey(c.getInt(0), c.getString(1)), c.getLong(2))) } }

    fun finishRemoval(key: StreamKey) = transaction { db ->
        db.delete("assignment", "service_id=? AND url=?", key.args())
        bumpRevision(db, key)
        db.delete("removal_intent", "service_id=? AND url=?", key.args())
        changed(db)
    }

    private fun createAiTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tag_command_receipt (device_id TEXT NOT NULL, catalog_epoch TEXT NOT NULL, client_id TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, required_scopes TEXT NOT NULL, result_json TEXT NOT NULL, retained_until_ms INTEGER NOT NULL, PRIMARY KEY(device_id,catalog_epoch,client_id,request_id))")
        db.execSQL("CREATE INDEX tag_receipt_expiry ON tag_command_receipt(retained_until_ms)")
        db.execSQL("CREATE TABLE ai_removal_intent (device_id TEXT NOT NULL, catalog_epoch TEXT NOT NULL, client_id TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, library_id INTEGER NOT NULL, service_id INTEGER NOT NULL, url TEXT NOT NULL, PRIMARY KEY(device_id,catalog_epoch,client_id,request_id,service_id,url))")
    }

    internal fun findReceipt(device: String, epoch: String, client: String, request: String): AiCommandReceipt? = readableDatabase.rawQuery(
        "SELECT device_id,catalog_epoch,client_id,request_id,fingerprint,required_scopes,result_json,retained_until_ms FROM tag_command_receipt WHERE device_id=? AND catalog_epoch=? AND client_id=? AND request_id=?",
        arrayOf(device, epoch, client, request)
    ).use { c -> if (c.moveToFirst()) AiCommandReceipt(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7)) else null }

    internal fun insertReceipt(receipt: AiCommandReceipt) {
        writableDatabase.execSQL("INSERT INTO tag_command_receipt VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>(receipt.deviceId, receipt.catalogEpoch, receipt.clientId, receipt.requestId, receipt.fingerprint, receipt.requiredScopes, receipt.resultJson, receipt.retainedUntilMs))
    }

    internal fun pruneReceipts(now: Long) {
        writableDatabase.delete("tag_command_receipt", "retained_until_ms<=?", arrayOf(now.toString()))
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM tag_command_receipt", null).use {
            it.moveToFirst()
            it.getLong(0)
        }
        if (count >= 10000) throw TagReceiptCapacityException()
    }

    internal data class AiRemoval(val device: String, val epoch: String, val client: String, val request: String, val fingerprint: String, val libraryId: Long, val key: StreamKey)

    internal fun stageAiRemovals(intents: List<AiRemoval>) = transaction { db ->
        for (i in intents) db.execSQL("INSERT INTO ai_removal_intent VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>(i.device, i.epoch, i.client, i.request, i.fingerprint, i.libraryId, i.key.serviceId, i.key.url))
    }

    internal fun pendingAiRemovals(): List<AiRemoval> = readableDatabase.rawQuery("SELECT device_id,catalog_epoch,client_id,request_id,fingerprint,library_id,service_id,url FROM ai_removal_intent", null).use { c ->
        buildList { while (c.moveToNext()) add(AiRemoval(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), StreamKey(c.getInt(6), c.getString(7)))) }
    }

    internal fun settleAiRemoval(intent: AiRemoval, committed: Boolean) = transaction { db ->
        if (committed) {
            db.delete("assignment", "service_id=? AND url=?", intent.key.args())
            bumpRevision(db, intent.key)
            changed(db)
        }
        db.delete("ai_removal_intent", "device_id=? AND catalog_epoch=? AND client_id=? AND request_id=? AND service_id=? AND url=?", arrayOf(intent.device, intent.epoch, intent.client, intent.request, intent.key.serviceId.toString(), intent.key.url))
    }

    /** Imported snapshots never carry executable cleanup intents or old command receipts. */
    internal fun discardRuntimeHistory() = transaction { db ->
        db.delete("removal_intent", null, null)
        db.delete("ai_removal_intent", null, null)
        db.delete("tag_command_receipt", null, null)
    }

    private fun nameValues(name: String) = ContentValues().apply {
        put("name", TagNames.clean(name))
        put("name_key", TagNames.key(name))
    }

    private fun StreamKey.args() = arrayOf(serviceId.toString(), url)
}
