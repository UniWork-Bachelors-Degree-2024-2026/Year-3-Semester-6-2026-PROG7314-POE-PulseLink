/*
 * Thread-safe SQLite Local Database managing user-scoped offline AD8232 ECG session metadata,
 * ordered 250 Hz telemetry batches, conflict retention, interrupted-upload recovery, and
 * durable notification inbox state.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Espressif Systems, 2023. ESP32-C3 Series Datasheet. [online] Available at: <https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Save data in a local database using SQLite. [online] Available at: <https://developer.android.com/training/data-storage/sqlite> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.local

import android.content.ContentValues                // (Google Developers, 2024)
import android.content.Context                      // (Google Developers, 2024)
import android.database.Cursor                      // (Google Developers, 2024)
import android.database.sqlite.SQLiteDatabase       // (Google Developers, 2024)
import android.database.sqlite.SQLiteOpenHelper     // (Google Developers, 2024)
import com.example.prog7314_poepart23.NurseSyncSummary
import com.example.prog7314_poepart23.data.local.models.CachedSampleBatch
import com.example.prog7314_poepart23.data.local.models.CachedSession
import com.example.prog7314_poepart23.data.local.models.QueueStatusBreakdown
import com.example.prog7314_poepart23.data.local.models.RecordingSyncStage
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.LocaleHelper

class OfflineEcgDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    OfflineQueueStore { // (Google Developers, 2024)

    companion object {
        private const val DATABASE_NAME = "pulselink_offline_ecg.db"
        private const val DATABASE_VERSION = 2

        // Sessions table
        private const val TABLE_SESSIONS = "cached_sessions"
        private const val COL_SESSION_LOCAL_ID = "local_id"
        private const val COL_SESSION_REMOTE_ID = "remote_session_id"
        private const val COL_SESSION_RECORDING_ID = "recording_id"
        private const val COL_SESSION_OWNER_UID = "owner_uid"
        private const val COL_SESSION_PATIENT_ID = "patient_id"
        private const val COL_SESSION_PATIENT_NAME = "patient_name"
        private const val COL_SESSION_DEVICE_ID = "device_id"
        private const val COL_SESSION_OPERATOR_UID = "operator_uid"
        private const val COL_SESSION_SITE_ID = "site_id"
        private const val COL_SESSION_GRANT_ID = "authorisation_grant_id"
        private const val COL_SESSION_BOOT_ID = "boot_id"
        private const val COL_SESSION_START_TIME = "start_time_iso"
        private const val COL_SESSION_END_TIME = "end_time_iso"
        private const val COL_SESSION_AVG_BPM = "avg_bpm"
        private const val COL_SESSION_DURATION = "duration_seconds"
        private const val COL_SESSION_EXPECTED_BATCHES = "expected_batch_count"
        private const val COL_SESSION_EXPECTED_SAMPLES = "expected_sample_count"
        private const val COL_SESSION_UPLOADED_BATCHES = "uploaded_batch_count"
        private const val COL_SESSION_UPLOADED_SAMPLES = "uploaded_sample_count"
        private const val COL_SESSION_STATUS = "status"
        private const val COL_SESSION_SYNC_STAGE = "sync_stage"
        private const val COL_SESSION_IS_SYNCED = "is_synced"
        private const val COL_SESSION_REPORT_READY = "report_ready"
        private const val COL_SESSION_RETRY_COUNT = "retry_count"
        private const val COL_SESSION_LAST_ATTEMPT_AT = "last_attempt_at_millis"
        private const val COL_SESSION_LAST_ERROR = "last_error"
        private const val COL_SESSION_CONFLICT_CODE = "conflict_code"
        private const val COL_SESSION_CONFLICT_REASON = "conflict_reason"
        private const val COL_SESSION_IN_FLIGHT_AT = "in_flight_at_millis"
        private const val COL_SESSION_CREATED_AT = "created_at_millis"

        // Sample batches table
        private const val TABLE_SAMPLE_BATCHES = "cached_sample_batches"
        private const val COL_BATCH_LOCAL_ID = "local_id"
        private const val COL_BATCH_SESSION_ID = "remote_session_id"
        private const val COL_BATCH_RECORDING_ID = "recording_id"
        private const val COL_BATCH_OWNER_UID = "owner_uid"
        private const val COL_BATCH_DEVICE_ID = "device_id"
        private const val COL_BATCH_PATIENT_ID = "patient_id"
        private const val COL_BATCH_SEQUENCE = "sequence_index"
        private const val COL_BATCH_FIRST_SAMPLE_INDEX = "first_sample_index"
        private const val COL_BATCH_SAMPLE_COUNT = "sample_count"
        private const val COL_BATCH_BOOT_ID = "boot_id"
        private const val COL_BATCH_SAMPLES_JSON = "samples_json"
        private const val COL_BATCH_SAMPLE_OFFSETS_JSON = "sample_offsets_us_json"
        private const val COL_BATCH_SAMPLE_FLAGS_JSON = "sample_flags_json"
        private const val COL_BATCH_FIRST_SAMPLE_TS_US = "first_sample_ts_us"
        private const val COL_BATCH_SAMPLE_RATE = "sample_rate_hz"
        private const val COL_BATCH_LEADS_OFF = "leads_off"
        private const val COL_BATCH_ESTIMATED_BPM = "estimated_bpm"
        private const val COL_BATCH_SIGNAL_QUALITY = "signal_quality"
        private const val COL_BATCH_SHA256 = "sha256"
        private const val COL_BATCH_TIMESTAMP = "timestamp_iso"
        private const val COL_BATCH_IS_SYNCED = "is_synced"
        private const val COL_BATCH_SYNC_STAGE = "sync_stage"
        private const val COL_BATCH_RETRY_COUNT = "retry_count"
        private const val COL_BATCH_LAST_ATTEMPT_AT = "last_attempt_at_millis"
        private const val COL_BATCH_LAST_ERROR = "last_error"
        private const val COL_BATCH_CONFLICT_CODE = "conflict_code"
        private const val COL_BATCH_CONFLICT_REASON = "conflict_reason"
        private const val COL_BATCH_IN_FLIGHT_AT = "in_flight_at_millis"
        private const val COL_BATCH_CREATED_AT = "created_at_millis"

        // User-scoped notifications table
        private const val TABLE_NOTIFICATIONS = "cached_notifications"
        private const val COL_NOTIF_LOCAL_ID = "local_id"
        private const val COL_NOTIF_OWNER_UID = "owner_uid"
        private const val COL_NOTIF_ID = "notification_id"
        private const val COL_NOTIF_TITLE = "title"
        private const val COL_NOTIF_MESSAGE = "message_preview"
        private const val COL_NOTIF_TIMESTAMP_LABEL = "timestamp_label"
        private const val COL_NOTIF_IS_UNREAD = "is_unread"
        private const val COL_NOTIF_IS_DELETED = "is_deleted"
        private const val COL_NOTIF_ICON_RES = "icon_res"
        private const val COL_NOTIF_SESSION_ID = "session_id"
        private const val COL_NOTIF_SESSION_TS_LABEL = "session_timestamp_label"
        private const val COL_NOTIF_AVG_BPM = "avg_bpm"
        private const val COL_NOTIF_DURATION_SECONDS = "duration_seconds"
        private const val COL_NOTIF_START_TIME_UTC = "start_time_utc"
        private const val COL_NOTIF_SIGNAL_QUALITY = "signal_quality"
        private const val COL_NOTIF_SESSION_STATUS = "session_status"
        private const val COL_NOTIF_RHYTHM_STATUS = "rhythm_status"
        private const val COL_NOTIF_CREATED_AT = "created_at_millis"

        // Sync metadata table
        private const val TABLE_SYNC_META = "sync_metadata"
        private const val COL_META_KEY = "meta_key"
        private const val COL_META_VALUE = "meta_value"
        private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"

        @Volatile
        private var INSTANCE: OfflineEcgDatabase? = null

        fun getInstance(context: Context): OfflineEcgDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineEcgDatabase(context).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createSessionsTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                $COL_SESSION_LOCAL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION_REMOTE_ID TEXT NOT NULL,
                $COL_SESSION_RECORDING_ID TEXT NOT NULL DEFAULT '',
                $COL_SESSION_OWNER_UID TEXT NOT NULL DEFAULT '',
                $COL_SESSION_PATIENT_ID TEXT NOT NULL,
                $COL_SESSION_PATIENT_NAME TEXT,
                $COL_SESSION_DEVICE_ID TEXT NOT NULL,
                $COL_SESSION_OPERATOR_UID TEXT,
                $COL_SESSION_SITE_ID TEXT,
                $COL_SESSION_GRANT_ID TEXT,
                $COL_SESSION_BOOT_ID TEXT NOT NULL DEFAULT 'boot_android_local',
                $COL_SESSION_START_TIME TEXT NOT NULL,
                $COL_SESSION_END_TIME TEXT,
                $COL_SESSION_AVG_BPM INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_DURATION INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_EXPECTED_BATCHES INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_EXPECTED_SAMPLES INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_UPLOADED_BATCHES INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_UPLOADED_SAMPLES INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_STATUS TEXT NOT NULL DEFAULT 'Normal',
                $COL_SESSION_SYNC_STAGE TEXT NOT NULL DEFAULT 'Pending',
                $COL_SESSION_IS_SYNCED INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_REPORT_READY INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_LAST_ATTEMPT_AT INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_LAST_ERROR TEXT,
                $COL_SESSION_CONFLICT_CODE INTEGER,
                $COL_SESSION_CONFLICT_REASON TEXT,
                $COL_SESSION_IN_FLIGHT_AT INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val createSampleBatchesTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_SAMPLE_BATCHES (
                $COL_BATCH_LOCAL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_BATCH_SESSION_ID TEXT NOT NULL,
                $COL_BATCH_RECORDING_ID TEXT NOT NULL DEFAULT '',
                $COL_BATCH_OWNER_UID TEXT NOT NULL DEFAULT '',
                $COL_BATCH_DEVICE_ID TEXT NOT NULL,
                $COL_BATCH_PATIENT_ID TEXT NOT NULL,
                $COL_BATCH_SEQUENCE INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_FIRST_SAMPLE_INDEX INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_SAMPLE_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_BOOT_ID TEXT NOT NULL DEFAULT 'boot_android_local',
                $COL_BATCH_SAMPLES_JSON TEXT NOT NULL,
                $COL_BATCH_SAMPLE_OFFSETS_JSON TEXT,
                $COL_BATCH_SAMPLE_FLAGS_JSON TEXT,
                $COL_BATCH_FIRST_SAMPLE_TS_US INTEGER,
                $COL_BATCH_SAMPLE_RATE INTEGER NOT NULL DEFAULT 250,
                $COL_BATCH_LEADS_OFF INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_ESTIMATED_BPM INTEGER NOT NULL DEFAULT 72,
                $COL_BATCH_SIGNAL_QUALITY TEXT NOT NULL DEFAULT 'Good',
                $COL_BATCH_SHA256 TEXT NOT NULL DEFAULT '',
                $COL_BATCH_TIMESTAMP TEXT NOT NULL,
                $COL_BATCH_IS_SYNCED INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_SYNC_STAGE TEXT NOT NULL DEFAULT 'Pending',
                $COL_BATCH_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_LAST_ATTEMPT_AT INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_LAST_ERROR TEXT,
                $COL_BATCH_CONFLICT_CODE INTEGER,
                $COL_BATCH_CONFLICT_REASON TEXT,
                $COL_BATCH_IN_FLIGHT_AT INTEGER NOT NULL DEFAULT 0,
                $COL_BATCH_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val createNotificationsTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_NOTIFICATIONS (
                $COL_NOTIF_LOCAL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NOTIF_OWNER_UID TEXT NOT NULL,
                $COL_NOTIF_ID TEXT NOT NULL,
                $COL_NOTIF_TITLE TEXT NOT NULL,
                $COL_NOTIF_MESSAGE TEXT NOT NULL,
                $COL_NOTIF_TIMESTAMP_LABEL TEXT NOT NULL,
                $COL_NOTIF_IS_UNREAD INTEGER NOT NULL DEFAULT 1,
                $COL_NOTIF_IS_DELETED INTEGER NOT NULL DEFAULT 0,
                $COL_NOTIF_ICON_RES INTEGER NOT NULL,
                $COL_NOTIF_SESSION_ID TEXT,
                $COL_NOTIF_SESSION_TS_LABEL TEXT,
                $COL_NOTIF_AVG_BPM INTEGER,
                $COL_NOTIF_DURATION_SECONDS INTEGER,
                $COL_NOTIF_START_TIME_UTC TEXT,
                $COL_NOTIF_SIGNAL_QUALITY TEXT,
                $COL_NOTIF_SESSION_STATUS TEXT,
                $COL_NOTIF_RHYTHM_STATUS TEXT,
                $COL_NOTIF_CREATED_AT INTEGER NOT NULL,
                UNIQUE($COL_NOTIF_OWNER_UID, $COL_NOTIF_ID)
            )
        """.trimIndent()

        val createSyncMetaTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_META (
                $COL_META_KEY TEXT PRIMARY KEY,
                $COL_META_VALUE TEXT NOT NULL
            )
        """.trimIndent()

        db.execSQL(createSessionsTable)
        db.execSQL(createSampleBatchesTable)
        db.execSQL(createNotificationsTable)
        db.execSQL(createSyncMetaTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Non-destructively upgrade version 1 tables so queued offline recordings are never lost
            val sessionColumns = listOf(
                "$COL_SESSION_RECORDING_ID TEXT NOT NULL DEFAULT ''",
                "$COL_SESSION_OWNER_UID TEXT NOT NULL DEFAULT ''",
                "$COL_SESSION_PATIENT_NAME TEXT",
                "$COL_SESSION_OPERATOR_UID TEXT",
                "$COL_SESSION_SITE_ID TEXT",
                "$COL_SESSION_GRANT_ID TEXT",
                "$COL_SESSION_BOOT_ID TEXT NOT NULL DEFAULT 'boot_android_local'",
                "$COL_SESSION_EXPECTED_BATCHES INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_EXPECTED_SAMPLES INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_UPLOADED_BATCHES INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_UPLOADED_SAMPLES INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_SYNC_STAGE TEXT NOT NULL DEFAULT 'Pending'",
                "$COL_SESSION_REPORT_READY INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_RETRY_COUNT INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_LAST_ATTEMPT_AT INTEGER NOT NULL DEFAULT 0",
                "$COL_SESSION_LAST_ERROR TEXT",
                "$COL_SESSION_CONFLICT_CODE INTEGER",
                "$COL_SESSION_CONFLICT_REASON TEXT",
                "$COL_SESSION_IN_FLIGHT_AT INTEGER NOT NULL DEFAULT 0"
            )
            for (columnDef in sessionColumns) {
                try {
                    db.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN $columnDef")
                } catch (_: Exception) {}
            }

            val batchColumns = listOf(
                "$COL_BATCH_RECORDING_ID TEXT NOT NULL DEFAULT ''",
                "$COL_BATCH_OWNER_UID TEXT NOT NULL DEFAULT ''",
                "$COL_BATCH_SEQUENCE INTEGER NOT NULL DEFAULT 0",
                "$COL_BATCH_FIRST_SAMPLE_INDEX INTEGER NOT NULL DEFAULT 0",
                "$COL_BATCH_SAMPLE_COUNT INTEGER NOT NULL DEFAULT 0",
                "$COL_BATCH_BOOT_ID TEXT NOT NULL DEFAULT 'boot_android_local'",
                "$COL_BATCH_SAMPLE_OFFSETS_JSON TEXT",
                "$COL_BATCH_SAMPLE_FLAGS_JSON TEXT",
                "$COL_BATCH_FIRST_SAMPLE_TS_US INTEGER",
                "$COL_BATCH_SIGNAL_QUALITY TEXT NOT NULL DEFAULT 'Good'",
                "$COL_BATCH_SHA256 TEXT NOT NULL DEFAULT ''",
                "$COL_BATCH_SYNC_STAGE TEXT NOT NULL DEFAULT 'Pending'",
                "$COL_BATCH_RETRY_COUNT INTEGER NOT NULL DEFAULT 0",
                "$COL_BATCH_LAST_ATTEMPT_AT INTEGER NOT NULL DEFAULT 0",
                "$COL_BATCH_LAST_ERROR TEXT",
                "$COL_BATCH_CONFLICT_CODE INTEGER",
                "$COL_BATCH_CONFLICT_REASON TEXT",
                "$COL_BATCH_IN_FLIGHT_AT INTEGER NOT NULL DEFAULT 0"
            )
            for (columnDef in batchColumns) {
                try {
                    db.execSQL("ALTER TABLE $TABLE_SAMPLE_BATCHES ADD COLUMN $columnDef")
                } catch (_: Exception) {}
            }

            onCreate(db)
        }
    }

    private fun userFilterClause(sessionTable: Boolean = true): String {
        return if (sessionTable) {
            "($COL_SESSION_OWNER_UID = ? OR $COL_SESSION_PATIENT_ID = ?)"
        } else {
            "($COL_BATCH_OWNER_UID = ? OR $COL_BATCH_PATIENT_ID = ?)"
        }
    }

    @Synchronized
    override fun insertSession(session: CachedSession): Long {
        val db = writableDatabase
        val existing = getSessionByRemoteOrRecordingIdInternal(db, session.remoteSessionId, session.recordingId)
        if (existing != null) {
            if (existing.matchesAuditIdentity(session)) {
                return existing.localId
            }
            val reason = LocaleHelper.stringOr(R.string.rt_db_audit_conflict, "Audit identity conflict for recording %1\$s: existing (%2\$s/%3\$s@%4\$s) vs incoming (%5\$s/%6\$s@%7\$s).", session.recordingId, existing.patientId, existing.deviceId, existing.startTimeIso, session.patientId, session.deviceId, session.startTimeIso)
            val conflictSession = session.copy(
                status = "Conflict",
                isSynced = false,
                syncStage = RecordingSyncStage.CONFLICT.wireValue,
                conflictCode = 409,
                conflictReason = reason,
                lastError = reason,
                inFlightAtMillis = 0L
            )
            return db.insert(TABLE_SESSIONS, null, sessionToContentValues(conflictSession))
        }

        val initialStage = if (session.syncStage.isNotBlank()) {
            session.syncStage
        } else {
            CachedSession.defaultSyncStage(session.status, session.isSynced)
        }
        val normalised = session.copy(
            recordingId = session.recordingId.ifBlank { CachedSession.extractOrCreateRecordingId(session.remoteSessionId) },
            ownerUid = session.ownerUid.ifBlank { session.patientId },
            syncStage = initialStage,
            reportReady = initialStage.equals(RecordingSyncStage.REPORT_READY.wireValue, ignoreCase = true)
        )
        return db.insert(TABLE_SESSIONS, null, sessionToContentValues(normalised))
    }

    private fun sessionToContentValues(session: CachedSession): ContentValues {
        return ContentValues().apply {
            put(COL_SESSION_REMOTE_ID, session.remoteSessionId)
            put(COL_SESSION_RECORDING_ID, session.recordingId)
            put(COL_SESSION_OWNER_UID, session.ownerUid)
            put(COL_SESSION_PATIENT_ID, session.patientId)
            put(COL_SESSION_PATIENT_NAME, session.patientName)
            put(COL_SESSION_DEVICE_ID, session.deviceId)
            put(COL_SESSION_OPERATOR_UID, session.operatorUid)
            put(COL_SESSION_SITE_ID, session.siteId)
            put(COL_SESSION_GRANT_ID, session.authorisationGrantId)
            put(COL_SESSION_BOOT_ID, session.bootId)
            put(COL_SESSION_START_TIME, session.startTimeIso)
            put(COL_SESSION_END_TIME, session.endTimeIso)
            put(COL_SESSION_AVG_BPM, session.avgBpm)
            put(COL_SESSION_DURATION, session.durationSeconds)
            put(COL_SESSION_EXPECTED_BATCHES, session.expectedBatchCount)
            put(COL_SESSION_EXPECTED_SAMPLES, session.expectedSampleCount)
            put(COL_SESSION_UPLOADED_BATCHES, session.uploadedBatchCount)
            put(COL_SESSION_UPLOADED_SAMPLES, session.uploadedSampleCount)
            put(COL_SESSION_STATUS, session.status)
            put(COL_SESSION_SYNC_STAGE, session.syncStage)
            put(COL_SESSION_IS_SYNCED, if (session.isSynced) 1 else 0)
            put(COL_SESSION_REPORT_READY, if (session.reportReady) 1 else 0)
            put(COL_SESSION_RETRY_COUNT, session.retryCount)
            put(COL_SESSION_LAST_ATTEMPT_AT, session.lastAttemptAtMillis)
            put(COL_SESSION_LAST_ERROR, session.lastError)
            if (session.conflictCode != null) put(COL_SESSION_CONFLICT_CODE, session.conflictCode) else putNull(COL_SESSION_CONFLICT_CODE)
            put(COL_SESSION_CONFLICT_REASON, session.conflictReason)
            put(COL_SESSION_IN_FLIGHT_AT, session.inFlightAtMillis)
            put(COL_SESSION_CREATED_AT, session.createdAtMillis)
        }
    }

    private fun getSessionByRemoteOrRecordingIdInternal(
        db: SQLiteDatabase,
        remoteSessionId: String,
        recordingId: String
    ): CachedSession? {
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_REMOTE_ID = ? OR ($COL_SESSION_RECORDING_ID != '' AND $COL_SESSION_RECORDING_ID = ?)",
            arrayOf(remoteSessionId, recordingId),
            null,
            null,
            "$COL_SESSION_LOCAL_ID ASC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) return cursorToSession(it)
        }
        return null
    }

    @Synchronized
    override fun getSessionByLocalId(localId: Long): CachedSession? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_LOCAL_ID = ?",
            arrayOf(localId.toString()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) return cursorToSession(it)
        }
        return null
    }

    @Synchronized
    override fun getSessionByRemoteOrRecordingId(id: String): CachedSession? {
        return getSessionByRemoteOrRecordingIdInternal(readableDatabase, id, id)
    }

    @Synchronized
    override fun getUnsyncedSessions(patientId: String): List<CachedSession> {
        val list = mutableListOf<CachedSession>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_IS_SYNCED = 0 AND ${userFilterClause(true)} AND $COL_SESSION_STATUS != 'Recording' " +
                "AND $COL_SESSION_SYNC_STAGE NOT IN ('SavedLocally', 'Conflict', 'ReportReady')",
            arrayOf(patientId, patientId),
            null,
            null,
            "$COL_SESSION_CREATED_AT ASC, $COL_SESSION_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSession(it))
            }
        }
        return list
    }

    @Synchronized
    override fun getRecoverableSessions(ownerOrPatientId: String): List<CachedSession> {
        val list = mutableListOf<CachedSession>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "${userFilterClause(true)} AND $COL_SESSION_SYNC_STAGE IN ('Pending', 'Uploaded', 'Processing')",
            arrayOf(ownerOrPatientId, ownerOrPatientId),
            null,
            null,
            "$COL_SESSION_CREATED_AT ASC, $COL_SESSION_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSession(it))
            }
        }
        return list
    }

    @Synchronized
    override fun getConflictedSessions(ownerOrPatientId: String?): List<CachedSession> {
        val list = mutableListOf<CachedSession>()
        val db = readableDatabase
        val selection = if (ownerOrPatientId.isNullOrBlank()) {
            "$COL_SESSION_SYNC_STAGE = 'Conflict' OR $COL_SESSION_CONFLICT_CODE IS NOT NULL"
        } else {
            "${userFilterClause(true)} AND ($COL_SESSION_SYNC_STAGE = 'Conflict' OR $COL_SESSION_CONFLICT_CODE IS NOT NULL)"
        }
        val args = if (ownerOrPatientId.isNullOrBlank()) null else arrayOf(ownerOrPatientId, ownerOrPatientId)
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            selection,
            args,
            null,
            null,
            "$COL_SESSION_CREATED_AT ASC, $COL_SESSION_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSession(it))
            }
        }
        return list
    }

    @Synchronized
    override fun getSessionsByStage(ownerOrPatientId: String, stage: RecordingSyncStage): List<CachedSession> {
        val list = mutableListOf<CachedSession>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "${userFilterClause(true)} AND $COL_SESSION_SYNC_STAGE = ?",
            arrayOf(ownerOrPatientId, ownerOrPatientId, stage.wireValue),
            null,
            null,
            "$COL_SESSION_CREATED_AT ASC, $COL_SESSION_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSession(it))
            }
        }
        return list
    }

    @Synchronized
    override fun getAllUserSessions(ownerOrPatientId: String): List<CachedSession> {
        val list = mutableListOf<CachedSession>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            userFilterClause(true),
            arrayOf(ownerOrPatientId, ownerOrPatientId),
            null,
            null,
            "$COL_SESSION_CREATED_AT DESC, $COL_SESSION_LOCAL_ID DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSession(it))
            }
        }
        return list
    }

    @Synchronized
    override fun markSessionSynced(localId: Long) {
        val existing = getSessionByLocalId(localId)
        val nextStage = if (existing?.stage == RecordingSyncStage.REPORT_READY) {
            RecordingSyncStage.REPORT_READY
        } else {
            RecordingSyncStage.PROCESSING
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_IS_SYNCED, 1)
            put(COL_SESSION_SYNC_STAGE, nextStage.wireValue)
            put(COL_SESSION_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun markSessionInFlight(localId: Long, inFlightAtMillis: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_IN_FLIGHT_AT, inFlightAtMillis)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun markSessionStage(
        localId: Long,
        stage: RecordingSyncStage,
        uploadedBatchCount: Int?,
        uploadedSampleCount: Int?,
        reportReady: Boolean,
        lastError: String?
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_SYNC_STAGE, stage.wireValue)
            if (stage == RecordingSyncStage.PROCESSING || stage == RecordingSyncStage.REPORT_READY) {
                put(COL_SESSION_IS_SYNCED, 1)
            }
            if (uploadedBatchCount != null) put(COL_SESSION_UPLOADED_BATCHES, uploadedBatchCount)
            if (uploadedSampleCount != null) put(COL_SESSION_UPLOADED_SAMPLES, uploadedSampleCount)
            put(COL_SESSION_REPORT_READY, if (reportReady) 1 else 0)
            put(COL_SESSION_LAST_ERROR, lastError)
            put(COL_SESSION_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun recordSessionRetry(localId: Long, errorMessage: String, attemptedAtMillis: Long) {
        val existing = getSessionByLocalId(localId) ?: return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_RETRY_COUNT, existing.retryCount + 1)
            put(COL_SESSION_LAST_ATTEMPT_AT, attemptedAtMillis)
            put(COL_SESSION_LAST_ERROR, errorMessage)
            put(COL_SESSION_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun markSessionConflict(localId: Long, conflictCode: Int, conflictReason: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_STATUS, "Conflict")
            put(COL_SESSION_IS_SYNCED, 0)
            put(COL_SESSION_SYNC_STAGE, RecordingSyncStage.CONFLICT.wireValue)
            put(COL_SESSION_CONFLICT_CODE, conflictCode)
            put(COL_SESSION_CONFLICT_REASON, conflictReason)
            put(COL_SESSION_LAST_ERROR, conflictReason)
            put(COL_SESSION_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun updateSessionRemoteId(localId: Long, remoteSessionId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_REMOTE_ID, remoteSessionId)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun updateSessionMetrics(remoteSessionId: String, avgBpm: Int, durationSeconds: Int, endTimeIso: String) {
        queueSessionForUpload(
            remoteSessionId = remoteSessionId,
            avgBpm = avgBpm,
            durationSeconds = durationSeconds,
            endTimeIso = endTimeIso
        )
    }

    @Synchronized
    override fun queueSessionForUpload(
        remoteSessionId: String,
        avgBpm: Int,
        durationSeconds: Int,
        endTimeIso: String,
        expectedBatchCount: Int?,
        expectedSampleCount: Int?
    ) {
        val existing = getSessionByRemoteOrRecordingId(remoteSessionId) ?: return
        if (existing.hasConflict) return

        val batches = getBatchesForSession(remoteSessionId)
        val computedBatches = expectedBatchCount ?: batches.size
        val computedSamples = expectedSampleCount ?: batches.sumOf { it.sampleCount }
        val uploadedBatches = batches.count { it.isSynced }
        val uploadedSamples = batches.filter { it.isSynced }.sumOf { it.sampleCount }

        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_STATUS, "Completed")
            put(COL_SESSION_AVG_BPM, avgBpm)
            put(COL_SESSION_DURATION, durationSeconds)
            put(COL_SESSION_END_TIME, endTimeIso)
            put(COL_SESSION_EXPECTED_BATCHES, computedBatches)
            put(COL_SESSION_EXPECTED_SAMPLES, computedSamples)
            put(COL_SESSION_UPLOADED_BATCHES, uploadedBatches)
            put(COL_SESSION_UPLOADED_SAMPLES, uploadedSamples)
            put(COL_SESSION_SYNC_STAGE, RecordingSyncStage.PENDING.wireValue)
            put(COL_SESSION_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_REMOTE_ID = ?", arrayOf(remoteSessionId))
    }

    @Synchronized
    override fun updateBatchesSessionId(oldSessionId: String, newSessionId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_BATCH_SESSION_ID, newSessionId)
        }
        db.update(TABLE_SAMPLE_BATCHES, values, "$COL_BATCH_SESSION_ID = ?", arrayOf(oldSessionId))
    }

    @Synchronized
    override fun deleteSession(sessionId: String) {
        writableDatabase.apply {
            beginTransaction()
            try {
                delete(TABLE_SAMPLE_BATCHES, "$COL_BATCH_SESSION_ID = ?", arrayOf(sessionId))
                delete(TABLE_SESSIONS, "$COL_SESSION_REMOTE_ID = ?", arrayOf(sessionId))
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    // Sample Batch Operations
    @Synchronized
    override fun insertSampleBatch(batch: CachedSampleBatch): Long {
        val db = writableDatabase
        val computedSha = batch.sha256.ifBlank {
            CachedSampleBatch.computeBatchSha256(
                deviceId = batch.deviceId,
                sequence = batch.sequence,
                firstSampleIndex = batch.firstSampleIndex,
                sampleRateHz = batch.sampleRateHz,
                bootId = batch.bootId,
                leadsOff = batch.leadsOff,
                samplesJson = batch.samplesJson
            )
        }
        val recordingId = batch.recordingId.ifBlank { CachedSession.extractOrCreateRecordingId(batch.remoteSessionId) }

        val existingCursor = db.query(
            TABLE_SAMPLE_BATCHES,
            null,
            "($COL_BATCH_SESSION_ID = ? OR ($COL_BATCH_RECORDING_ID != '' AND $COL_BATCH_RECORDING_ID = ?)) " +
                "AND $COL_BATCH_SEQUENCE = ? AND $COL_BATCH_SYNC_STAGE != 'Conflict'",
            arrayOf(batch.remoteSessionId, recordingId, batch.sequence.toString()),
            null,
            null,
            "$COL_BATCH_LOCAL_ID ASC",
            "1"
        )
        val existing = existingCursor.use { if (it.moveToFirst()) cursorToSampleBatch(it) else null }
        if (existing != null) {
            if (existing.sha256.equals(computedSha, ignoreCase = true)) {
                return existing.localId
            }
            val reason = LocaleHelper.stringOr(R.string.rt_db_checksum_conflict, "Batch sequence %1\$s for session %2\$s already exists with a different SHA-256 checksum.", batch.sequence, batch.remoteSessionId)
            val conflictBatch = batch.copy(
                recordingId = recordingId,
                ownerUid = batch.ownerUid.ifBlank { batch.patientId },
                sha256 = computedSha,
                isSynced = false,
                syncStage = RecordingSyncStage.CONFLICT.wireValue,
                conflictCode = 409,
                conflictReason = reason,
                lastError = reason,
                inFlightAtMillis = 0L
            )
            val id = db.insert(TABLE_SAMPLE_BATCHES, null, batchToContentValues(conflictBatch))
            val parent = getSessionByRemoteOrRecordingIdInternal(db, batch.remoteSessionId, recordingId)
            if (parent != null) {
                markSessionConflict(parent.localId, 409, reason)
            }
            return id
        }

        val normalised = batch.copy(
            recordingId = recordingId,
            ownerUid = batch.ownerUid.ifBlank { batch.patientId },
            sha256 = computedSha,
            signalQuality = CachedSampleBatch.normaliseSignalQuality(batch.signalQuality, batch.leadsOff),
            sampleCount = if (batch.sampleCount > 0) batch.sampleCount else CachedSampleBatch.countSamples(batch.samplesJson)
        )
        return db.insert(TABLE_SAMPLE_BATCHES, null, batchToContentValues(normalised))
    }

    private fun batchToContentValues(batch: CachedSampleBatch): ContentValues {
        return ContentValues().apply {
            put(COL_BATCH_SESSION_ID, batch.remoteSessionId)
            put(COL_BATCH_RECORDING_ID, batch.recordingId)
            put(COL_BATCH_OWNER_UID, batch.ownerUid)
            put(COL_BATCH_DEVICE_ID, batch.deviceId)
            put(COL_BATCH_PATIENT_ID, batch.patientId)
            put(COL_BATCH_SEQUENCE, batch.sequence)
            put(COL_BATCH_FIRST_SAMPLE_INDEX, batch.firstSampleIndex)
            put(COL_BATCH_SAMPLE_COUNT, batch.sampleCount)
            put(COL_BATCH_BOOT_ID, batch.bootId)
            put(COL_BATCH_SAMPLES_JSON, batch.samplesJson)
            put(COL_BATCH_SAMPLE_OFFSETS_JSON, batch.sampleOffsetsUsJson)
            put(COL_BATCH_SAMPLE_FLAGS_JSON, batch.sampleFlagsJson)
            if (batch.firstSampleTimestampUs != null) put(COL_BATCH_FIRST_SAMPLE_TS_US, batch.firstSampleTimestampUs) else putNull(COL_BATCH_FIRST_SAMPLE_TS_US)
            put(COL_BATCH_SAMPLE_RATE, batch.sampleRateHz)
            put(COL_BATCH_LEADS_OFF, if (batch.leadsOff) 1 else 0)
            put(COL_BATCH_ESTIMATED_BPM, batch.estimatedBpm)
            put(COL_BATCH_SIGNAL_QUALITY, batch.signalQuality)
            put(COL_BATCH_SHA256, batch.sha256)
            put(COL_BATCH_TIMESTAMP, batch.timestampIso)
            put(COL_BATCH_IS_SYNCED, if (batch.isSynced) 1 else 0)
            put(COL_BATCH_SYNC_STAGE, batch.syncStage)
            put(COL_BATCH_RETRY_COUNT, batch.retryCount)
            put(COL_BATCH_LAST_ATTEMPT_AT, batch.lastAttemptAtMillis)
            put(COL_BATCH_LAST_ERROR, batch.lastError)
            if (batch.conflictCode != null) put(COL_BATCH_CONFLICT_CODE, batch.conflictCode) else putNull(COL_BATCH_CONFLICT_CODE)
            put(COL_BATCH_CONFLICT_REASON, batch.conflictReason)
            put(COL_BATCH_IN_FLIGHT_AT, batch.inFlightAtMillis)
            put(COL_BATCH_CREATED_AT, batch.createdAtMillis)
        }
    }

    @Synchronized
    override fun getUnsyncedSampleBatches(patientId: String): List<CachedSampleBatch> {
        val list = mutableListOf<CachedSampleBatch>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SAMPLE_BATCHES,
            null,
            "$COL_BATCH_IS_SYNCED = 0 AND ${userFilterClause(false)} AND $COL_BATCH_SYNC_STAGE != 'Conflict'",
            arrayOf(patientId, patientId),
            null,
            null,
            "$COL_BATCH_SESSION_ID ASC, $COL_BATCH_SEQUENCE ASC, $COL_BATCH_FIRST_SAMPLE_INDEX ASC, $COL_BATCH_CREATED_AT ASC, $COL_BATCH_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSampleBatch(it))
            }
        }
        return list
    }

    @Synchronized
    override fun getUnsyncedSampleBatchesForSession(sessionId: String): List<CachedSampleBatch> {
        val list = mutableListOf<CachedSampleBatch>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SAMPLE_BATCHES,
            null,
            "$COL_BATCH_IS_SYNCED = 0 AND $COL_BATCH_SESSION_ID = ? AND $COL_BATCH_SYNC_STAGE != 'Conflict'",
            arrayOf(sessionId),
            null,
            null,
            "$COL_BATCH_SEQUENCE ASC, $COL_BATCH_FIRST_SAMPLE_INDEX ASC, $COL_BATCH_CREATED_AT ASC, $COL_BATCH_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSampleBatch(it))
            }
        }
        return list
    }

    @Synchronized
    override fun markBatchInFlight(localId: Long, inFlightAtMillis: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_BATCH_IN_FLIGHT_AT, inFlightAtMillis)
        }
        db.update(TABLE_SAMPLE_BATCHES, values, "$COL_BATCH_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun markSampleBatchSynced(localId: Long) {
        val db = writableDatabase
        var sessionId: String? = null
        db.query(
            TABLE_SAMPLE_BATCHES,
            arrayOf(COL_BATCH_SESSION_ID),
            "$COL_BATCH_LOCAL_ID = ?",
            arrayOf(localId.toString()),
            null,
            null,
            null,
            "1"
        ).use {
            if (it.moveToFirst()) sessionId = it.getString(0)
        }

        val values = ContentValues().apply {
            put(COL_BATCH_IS_SYNCED, 1)
            put(COL_BATCH_SYNC_STAGE, RecordingSyncStage.UPLOADED.wireValue)
            putNull(COL_BATCH_LAST_ERROR)
            put(COL_BATCH_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SAMPLE_BATCHES, values, "$COL_BATCH_LOCAL_ID = ?", arrayOf(localId.toString()))

        if (!sessionId.isNullOrBlank()) {
            recomputeSessionProgressInternal(sessionId!!)
        }
    }

    @Synchronized
    override fun recordBatchRetry(localId: Long, errorMessage: String, attemptedAtMillis: Long) {
        val db = writableDatabase
        var currentRetries = 0
        db.query(
            TABLE_SAMPLE_BATCHES,
            arrayOf(COL_BATCH_RETRY_COUNT),
            "$COL_BATCH_LOCAL_ID = ?",
            arrayOf(localId.toString()),
            null,
            null,
            null,
            "1"
        ).use {
            if (it.moveToFirst()) currentRetries = it.getInt(0)
        }
        val values = ContentValues().apply {
            put(COL_BATCH_RETRY_COUNT, currentRetries + 1)
            put(COL_BATCH_LAST_ATTEMPT_AT, attemptedAtMillis)
            put(COL_BATCH_LAST_ERROR, errorMessage)
            put(COL_BATCH_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SAMPLE_BATCHES, values, "$COL_BATCH_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    override fun markBatchConflict(localId: Long, conflictCode: Int, conflictReason: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_BATCH_IS_SYNCED, 0)
            put(COL_BATCH_SYNC_STAGE, RecordingSyncStage.CONFLICT.wireValue)
            put(COL_BATCH_CONFLICT_CODE, conflictCode)
            put(COL_BATCH_CONFLICT_REASON, conflictReason)
            put(COL_BATCH_LAST_ERROR, conflictReason)
            put(COL_BATCH_IN_FLIGHT_AT, 0L)
        }
        db.update(TABLE_SAMPLE_BATCHES, values, "$COL_BATCH_LOCAL_ID = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    private fun recomputeSessionProgressInternal(sessionId: String) {
        val session = getSessionByRemoteOrRecordingId(sessionId) ?: return
        if (session.hasConflict) return
        val batches = getBatchesForSession(sessionId)
        val uploadedBatches = batches.count { it.isSynced }
        val uploadedSamples = batches.filter { it.isSynced }.sumOf { it.sampleCount }
        val expectedBatches = if (session.expectedBatchCount > 0) session.expectedBatchCount else batches.size
        val expectedSamples = if (session.expectedSampleCount > 0) session.expectedSampleCount else batches.sumOf { it.sampleCount }
        val nextStage = when {
            session.stage == RecordingSyncStage.REPORT_READY -> RecordingSyncStage.REPORT_READY
            session.stage == RecordingSyncStage.PROCESSING -> RecordingSyncStage.PROCESSING
            session.stage == RecordingSyncStage.SAVED_LOCALLY -> RecordingSyncStage.SAVED_LOCALLY
            expectedBatches > 0 && uploadedBatches >= expectedBatches && batches.all { it.isSynced } ->
                RecordingSyncStage.UPLOADED
            else -> RecordingSyncStage.PENDING
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_EXPECTED_BATCHES, expectedBatches)
            put(COL_SESSION_EXPECTED_SAMPLES, expectedSamples)
            put(COL_SESSION_UPLOADED_BATCHES, uploadedBatches)
            put(COL_SESSION_UPLOADED_SAMPLES, uploadedSamples)
            put(COL_SESSION_SYNC_STAGE, nextStage.wireValue)
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_LOCAL_ID = ?", arrayOf(session.localId.toString()))
    }

    @Synchronized
    override fun getBatchesForSession(sessionId: String): List<CachedSampleBatch> {
        val list = mutableListOf<CachedSampleBatch>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SAMPLE_BATCHES,
            null,
            "$COL_BATCH_SESSION_ID = ? AND $COL_BATCH_SYNC_STAGE != 'Conflict'",
            arrayOf(sessionId),
            null,
            null,
            "$COL_BATCH_SEQUENCE ASC, $COL_BATCH_FIRST_SAMPLE_INDEX ASC, $COL_BATCH_CREATED_AT ASC, $COL_BATCH_LOCAL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSampleBatch(it))
            }
        }
        return list
    }

    @Synchronized
    fun getSamplesForSession(sessionId: String): List<Int> {
        val batches = getBatchesForSession(sessionId)
        if (batches.isEmpty()) return emptyList()
        val all = mutableListOf<Int>()
        for (b in batches) {
            try {
                val arr = com.google.gson.Gson().fromJson(b.samplesJson, Array<Int>::class.java)
                if (arr != null) all.addAll(arr)
            } catch (_: Exception) {}
        }
        return all
    }

    @Synchronized
    override fun recoverInterruptedUploads(ownerOrPatientId: String?): Int {
        val db = writableDatabase
        var recovered = 0

        val batchWhere = if (ownerOrPatientId.isNullOrBlank()) {
            "$COL_BATCH_IN_FLIGHT_AT > 0"
        } else {
            "${userFilterClause(false)} AND $COL_BATCH_IN_FLIGHT_AT > 0"
        }
        val batchArgs = if (ownerOrPatientId.isNullOrBlank()) null else arrayOf(ownerOrPatientId, ownerOrPatientId)
        val resetBatchValues = ContentValues().apply { put(COL_BATCH_IN_FLIGHT_AT, 0L) }
        recovered += db.update(TABLE_SAMPLE_BATCHES, resetBatchValues, batchWhere, batchArgs)

        val sessionWhere = if (ownerOrPatientId.isNullOrBlank()) {
            "$COL_SESSION_IN_FLIGHT_AT > 0"
        } else {
            "${userFilterClause(true)} AND $COL_SESSION_IN_FLIGHT_AT > 0"
        }
        val sessionArgs = if (ownerOrPatientId.isNullOrBlank()) null else arrayOf(ownerOrPatientId, ownerOrPatientId)
        val resetSessionValues = ContentValues().apply { put(COL_SESSION_IN_FLIGHT_AT, 0L) }
        recovered += db.update(TABLE_SESSIONS, resetSessionValues, sessionWhere, sessionArgs)

        val sessionsToReconcile = if (ownerOrPatientId.isNullOrBlank()) {
            val all = mutableListOf<CachedSession>()
            db.query(
                TABLE_SESSIONS,
                null,
                "$COL_SESSION_SYNC_STAGE NOT IN ('SavedLocally', 'Conflict', 'ReportReady')",
                null,
                null,
                null,
                null
            ).use {
                while (it.moveToNext()) all.add(cursorToSession(it))
            }
            all
        } else {
            getRecoverableSessions(ownerOrPatientId)
        }
        for (s in sessionsToReconcile) {
            recomputeSessionProgressInternal(s.remoteSessionId)
        }
        return recovered
    }

    // Metrics & Sync Metadata
    @Synchronized
    override fun getPendingSyncCount(patientId: String): Int {
        val db = readableDatabase
        var count = 0

        val sessionCursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_SESSIONS WHERE $COL_SESSION_IS_SYNCED = 0 AND ${userFilterClause(true)} " +
                "AND $COL_SESSION_STATUS != 'Recording' AND $COL_SESSION_SYNC_STAGE NOT IN ('SavedLocally', 'Conflict', 'ReportReady')",
            arrayOf(patientId, patientId)
        )
        sessionCursor.use {
            if (it.moveToFirst()) count += it.getInt(0)
        }

        val batchCursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_SAMPLE_BATCHES WHERE $COL_BATCH_IS_SYNCED = 0 AND ${userFilterClause(false)} AND $COL_BATCH_SYNC_STAGE != 'Conflict'",
            arrayOf(patientId, patientId)
        )
        batchCursor.use {
            if (it.moveToFirst()) count += it.getInt(0)
        }

        return count
    }

    @Synchronized
    override fun getQueueStatusBreakdown(ownerOrPatientId: String?, deviceId: String?): QueueStatusBreakdown {
        val db = readableDatabase
        val cleanOwner = ownerOrPatientId?.trim()?.ifBlank { null }
        val cleanDevice = deviceId?.trim()?.ifBlank { null }

        fun countByStage(stage: RecordingSyncStage): Int {
            val clauses = mutableListOf("$COL_SESSION_SYNC_STAGE = ?")
            val args = mutableListOf(stage.wireValue)
            if (cleanOwner != null) {
                clauses.add(userFilterClause(true))
                args.add(cleanOwner)
                args.add(cleanOwner)
            }
            if (cleanDevice != null) {
                clauses.add("$COL_SESSION_DEVICE_ID = ?")
                args.add(cleanDevice)
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_SESSIONS WHERE ${clauses.joinToString(" AND ")}",
                args.toTypedArray()
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

        val batchClauses = mutableListOf("$COL_BATCH_IS_SYNCED = 0", "$COL_BATCH_SYNC_STAGE != 'Conflict'")
        val batchArgs = mutableListOf<String>()
        if (cleanOwner != null) {
            batchClauses.add(userFilterClause(false))
            batchArgs.add(cleanOwner)
            batchArgs.add(cleanOwner)
        }
        if (cleanDevice != null) {
            batchClauses.add("$COL_BATCH_DEVICE_ID = ?")
            batchArgs.add(cleanDevice)
        }
        val pendingBatches = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_SAMPLE_BATCHES WHERE ${batchClauses.joinToString(" AND ")}",
            if (batchArgs.isEmpty()) null else batchArgs.toTypedArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        return QueueStatusBreakdown(
            savedLocallyCount = countByStage(RecordingSyncStage.SAVED_LOCALLY),
            pendingCount = countByStage(RecordingSyncStage.PENDING),
            uploadedCount = countByStage(RecordingSyncStage.UPLOADED),
            processingCount = countByStage(RecordingSyncStage.PROCESSING),
            reportReadyCount = countByStage(RecordingSyncStage.REPORT_READY),
            conflictCount = countByStage(RecordingSyncStage.CONFLICT),
            failedCount = countByStage(RecordingSyncStage.FAILED),
            pendingBatchCount = pendingBatches,
            lastSyncedAtMillis = getLastSyncTimestamp().takeIf { it > 0L }
        )
    }

    // Returns a NurseSyncSummary for a specific device or across all local cached sessions.
    // Local-only recordings are never counted as uploaded.
    @Synchronized
    override fun getDeviceSyncSummary(deviceId: String?): NurseSyncSummary {
        val breakdown = getQueueStatusBreakdown(ownerOrPatientId = null, deviceId = deviceId)
        return NurseSyncSummary(
            local = breakdown.savedLocallyCount + breakdown.conflictCount + breakdown.failedCount,
            synchronising = breakdown.pendingCount + breakdown.uploadedCount + breakdown.processingCount,
            uploaded = breakdown.reportReadyCount,
            updatedAtMillis = breakdown.lastSyncedAtMillis
        )
    }

    @Synchronized
    override fun recordSyncTimestamp(millis: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_META_KEY, KEY_LAST_SYNC_TIME)
            put(COL_META_VALUE, millis.toString())
        }
        db.insertWithOnConflict(TABLE_SYNC_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Releases transient in-flight upload markers when an account signs out without destroying
    // durable user-scoped queues, retained conflicts, or persistent notification read states.
    @Synchronized
    override fun onUserSignedOut() {
        recoverInterruptedUploads(null)
    }

    // Wipes every cached row across all tables (reserved for explicit full reset or test cleanup).
    @Synchronized
    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_SESSIONS, null, null)
        db.delete(TABLE_SAMPLE_BATCHES, null, null)
        db.delete(TABLE_NOTIFICATIONS, null, null)
        db.delete(TABLE_SYNC_META, null, null)
    }

    @Synchronized
    override fun getLastSyncTimestamp(): Long {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SYNC_META,
            arrayOf(COL_META_VALUE),
            "$COL_META_KEY = ?",
            arrayOf(KEY_LAST_SYNC_TIME),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0).toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    // User-scoped durable Notification Inbox operations
    @Synchronized
    override fun upsertNotification(ownerUid: String, item: NotificationItem, createdAtMillis: Long): Boolean {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty() || item.notificationId.isBlank()) return false
        val db = writableDatabase

        var existingUnread: Boolean? = null
        var existingDeleted = false
        db.query(
            TABLE_NOTIFICATIONS,
            arrayOf(COL_NOTIF_IS_UNREAD, COL_NOTIF_IS_DELETED),
            "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_ID = ?",
            arrayOf(cleanOwner, item.notificationId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                existingUnread = cursor.getInt(0) == 1
                existingDeleted = cursor.getInt(1) == 1
            }
        }

        if (existingDeleted) {
            return false
        }

        if (existingUnread != null) {
            val preservedUnread = existingUnread == true && item.isUnread
            val updateValues = ContentValues().apply {
                put(COL_NOTIF_TITLE, item.title)
                put(COL_NOTIF_MESSAGE, item.messagePreview)
                put(COL_NOTIF_TIMESTAMP_LABEL, item.timestampLabel)
                put(COL_NOTIF_IS_UNREAD, if (preservedUnread) 1 else 0)
                put(COL_NOTIF_ICON_RES, item.iconRes)
                put(COL_NOTIF_SESSION_ID, item.sessionId)
                put(COL_NOTIF_SESSION_TS_LABEL, item.sessionTimestampLabel)
                if (item.avgBpm != null) put(COL_NOTIF_AVG_BPM, item.avgBpm) else putNull(COL_NOTIF_AVG_BPM)
                if (item.durationSeconds != null) put(COL_NOTIF_DURATION_SECONDS, item.durationSeconds) else putNull(COL_NOTIF_DURATION_SECONDS)
                put(COL_NOTIF_START_TIME_UTC, item.startTimeUtc)
                put(COL_NOTIF_SIGNAL_QUALITY, item.signalQuality)
                put(COL_NOTIF_SESSION_STATUS, item.sessionStatus)
                put(COL_NOTIF_RHYTHM_STATUS, item.rhythmStatus)
            }
            db.update(
                TABLE_NOTIFICATIONS,
                updateValues,
                "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_ID = ?",
                arrayOf(cleanOwner, item.notificationId)
            )
            return false
        }

        val insertValues = ContentValues().apply {
            put(COL_NOTIF_OWNER_UID, cleanOwner)
            put(COL_NOTIF_ID, item.notificationId)
            put(COL_NOTIF_TITLE, item.title)
            put(COL_NOTIF_MESSAGE, item.messagePreview)
            put(COL_NOTIF_TIMESTAMP_LABEL, item.timestampLabel)
            put(COL_NOTIF_IS_UNREAD, if (item.isUnread) 1 else 0)
            put(COL_NOTIF_IS_DELETED, 0)
            put(COL_NOTIF_ICON_RES, item.iconRes)
            put(COL_NOTIF_SESSION_ID, item.sessionId)
            put(COL_NOTIF_SESSION_TS_LABEL, item.sessionTimestampLabel)
            if (item.avgBpm != null) put(COL_NOTIF_AVG_BPM, item.avgBpm) else putNull(COL_NOTIF_AVG_BPM)
            if (item.durationSeconds != null) put(COL_NOTIF_DURATION_SECONDS, item.durationSeconds) else putNull(COL_NOTIF_DURATION_SECONDS)
            put(COL_NOTIF_START_TIME_UTC, item.startTimeUtc)
            put(COL_NOTIF_SIGNAL_QUALITY, item.signalQuality)
            put(COL_NOTIF_SESSION_STATUS, item.sessionStatus)
            put(COL_NOTIF_RHYTHM_STATUS, item.rhythmStatus)
            put(COL_NOTIF_CREATED_AT, createdAtMillis)
        }
        return db.insertWithOnConflict(TABLE_NOTIFICATIONS, null, insertValues, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    override fun getNotificationsForUser(ownerUid: String): List<NotificationItem> {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty()) return emptyList()
        val list = mutableListOf<NotificationItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NOTIFICATIONS,
            null,
            "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_IS_DELETED = 0",
            arrayOf(cleanOwner),
            null,
            null,
            "$COL_NOTIF_CREATED_AT DESC, $COL_NOTIF_LOCAL_ID DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToNotification(it))
            }
        }
        return list
    }

    @Synchronized
    override fun getNotificationById(ownerUid: String, notificationId: String): NotificationItem? {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty() || notificationId.isBlank()) return null
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NOTIFICATIONS,
            null,
            "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_ID = ? AND $COL_NOTIF_IS_DELETED = 0",
            arrayOf(cleanOwner, notificationId),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) return cursorToNotification(it)
        }
        return null
    }

    @Synchronized
    override fun markNotificationRead(ownerUid: String, notificationId: String) {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty() || notificationId.isBlank()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_UNREAD, 0)
        }
        db.update(
            TABLE_NOTIFICATIONS,
            values,
            "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_ID = ?",
            arrayOf(cleanOwner, notificationId)
        )
    }

    @Synchronized
    override fun markAllNotificationsRead(ownerUid: String) {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_UNREAD, 0)
        }
        db.update(
            TABLE_NOTIFICATIONS,
            values,
            "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_IS_DELETED = 0",
            arrayOf(cleanOwner)
        )
    }

    @Synchronized
    override fun deleteNotification(ownerUid: String, notificationId: String) {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty() || notificationId.isBlank()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_UNREAD, 0)
            put(COL_NOTIF_IS_DELETED, 1)
        }
        db.update(
            TABLE_NOTIFICATIONS,
            values,
            "$COL_NOTIF_OWNER_UID = ? AND $COL_NOTIF_ID = ?",
            arrayOf(cleanOwner, notificationId)
        )
    }

    private fun cursorToSession(cursor: Cursor): CachedSession {
        val remoteId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_REMOTE_ID))
        val patientId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_PATIENT_ID))
        val status = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_STATUS))
        val isSynced = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SESSION_IS_SYNCED)) == 1
        val recordingIdIdx = cursor.getColumnIndex(COL_SESSION_RECORDING_ID)
        val ownerUidIdx = cursor.getColumnIndex(COL_SESSION_OWNER_UID)
        val syncStageIdx = cursor.getColumnIndex(COL_SESSION_SYNC_STAGE)
        val conflictCodeIdx = cursor.getColumnIndex(COL_SESSION_CONFLICT_CODE)

        val rawRecordingId = if (recordingIdIdx >= 0) cursor.getString(recordingIdIdx) else null
        val rawOwnerUid = if (ownerUidIdx >= 0) cursor.getString(ownerUidIdx) else null
        val rawStage = if (syncStageIdx >= 0) cursor.getString(syncStageIdx) else null

        return CachedSession(
            localId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_LOCAL_ID)),
            remoteSessionId = remoteId,
            recordingId = rawRecordingId?.takeIf { it.isNotBlank() } ?: CachedSession.extractOrCreateRecordingId(remoteId),
            ownerUid = rawOwnerUid?.takeIf { it.isNotBlank() } ?: patientId,
            patientId = patientId,
            patientName = cursor.getColumnIndex(COL_SESSION_PATIENT_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_DEVICE_ID)),
            operatorUid = cursor.getColumnIndex(COL_SESSION_OPERATOR_UID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            siteId = cursor.getColumnIndex(COL_SESSION_SITE_ID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            authorisationGrantId = cursor.getColumnIndex(COL_SESSION_GRANT_ID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            bootId = cursor.getColumnIndex(COL_SESSION_BOOT_ID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString) ?: "boot_android_local",
            startTimeIso = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_START_TIME)),
            endTimeIso = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_END_TIME)),
            avgBpm = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SESSION_AVG_BPM)),
            durationSeconds = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SESSION_DURATION)),
            expectedBatchCount = cursor.getColumnIndex(COL_SESSION_EXPECTED_BATCHES).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0,
            expectedSampleCount = cursor.getColumnIndex(COL_SESSION_EXPECTED_SAMPLES).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0,
            uploadedBatchCount = cursor.getColumnIndex(COL_SESSION_UPLOADED_BATCHES).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0,
            uploadedSampleCount = cursor.getColumnIndex(COL_SESSION_UPLOADED_SAMPLES).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0,
            status = status,
            syncStage = rawStage?.takeIf { it.isNotBlank() } ?: CachedSession.defaultSyncStage(status, isSynced),
            isSynced = isSynced,
            reportReady = cursor.getColumnIndex(COL_SESSION_REPORT_READY).takeIf { it >= 0 }?.let { cursor.getInt(it) == 1 } ?: false,
            retryCount = cursor.getColumnIndex(COL_SESSION_RETRY_COUNT).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0,
            lastAttemptAtMillis = cursor.getColumnIndex(COL_SESSION_LAST_ATTEMPT_AT).takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L,
            lastError = cursor.getColumnIndex(COL_SESSION_LAST_ERROR).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            conflictCode = if (conflictCodeIdx >= 0 && !cursor.isNull(conflictCodeIdx)) cursor.getInt(conflictCodeIdx) else null,
            conflictReason = cursor.getColumnIndex(COL_SESSION_CONFLICT_REASON).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            inFlightAtMillis = cursor.getColumnIndex(COL_SESSION_IN_FLIGHT_AT).takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L,
            createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_CREATED_AT))
        )
    }

    private fun cursorToSampleBatch(cursor: Cursor): CachedSampleBatch {
        val remoteId = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_SESSION_ID))
        val patientId = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_PATIENT_ID))
        val deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_DEVICE_ID))
        val samplesJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_SAMPLES_JSON))
        val sampleRate = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BATCH_SAMPLE_RATE))
        val leadsOff = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BATCH_LEADS_OFF)) == 1
        val isSynced = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BATCH_IS_SYNCED)) == 1
        val sequence = cursor.getColumnIndex(COL_BATCH_SEQUENCE).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0
        val firstSampleIndex = cursor.getColumnIndex(COL_BATCH_FIRST_SAMPLE_INDEX).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0
        val bootId = cursor.getColumnIndex(COL_BATCH_BOOT_ID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString) ?: "boot_android_local"
        val rawSha = cursor.getColumnIndex(COL_BATCH_SHA256).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        val conflictCodeIdx = cursor.getColumnIndex(COL_BATCH_CONFLICT_CODE)
        val firstSampleTsIdx = cursor.getColumnIndex(COL_BATCH_FIRST_SAMPLE_TS_US)

        return CachedSampleBatch(
            localId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BATCH_LOCAL_ID)),
            remoteSessionId = remoteId,
            recordingId = cursor.getColumnIndex(COL_BATCH_RECORDING_ID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                ?.takeIf { it.isNotBlank() } ?: CachedSession.extractOrCreateRecordingId(remoteId),
            ownerUid = cursor.getColumnIndex(COL_BATCH_OWNER_UID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                ?.takeIf { it.isNotBlank() } ?: patientId,
            deviceId = deviceId,
            patientId = patientId,
            sequence = sequence,
            firstSampleIndex = firstSampleIndex,
            sampleCount = cursor.getColumnIndex(COL_BATCH_SAMPLE_COUNT).takeIf { it >= 0 }?.let(cursor::getInt)
                ?.takeIf { it > 0 } ?: CachedSampleBatch.countSamples(samplesJson),
            bootId = bootId,
            samplesJson = samplesJson,
            sampleOffsetsUsJson = cursor.getColumnIndex(COL_BATCH_SAMPLE_OFFSETS_JSON).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            sampleFlagsJson = cursor.getColumnIndex(COL_BATCH_SAMPLE_FLAGS_JSON).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            firstSampleTimestampUs = if (firstSampleTsIdx >= 0 && !cursor.isNull(firstSampleTsIdx)) cursor.getLong(firstSampleTsIdx) else null,
            sampleRateHz = sampleRate,
            leadsOff = leadsOff,
            estimatedBpm = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BATCH_ESTIMATED_BPM)),
            signalQuality = cursor.getColumnIndex(COL_BATCH_SIGNAL_QUALITY).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                ?: CachedSampleBatch.normaliseSignalQuality(null, leadsOff),
            sha256 = rawSha?.takeIf { it.isNotBlank() } ?: CachedSampleBatch.computeBatchSha256(
                deviceId = deviceId,
                sequence = sequence,
                firstSampleIndex = firstSampleIndex,
                sampleRateHz = sampleRate,
                bootId = bootId,
                leadsOff = leadsOff,
                samplesJson = samplesJson
            ),
            timestampIso = cursor.getString(cursor.getColumnIndexOrThrow(COL_BATCH_TIMESTAMP)),
            isSynced = isSynced,
            syncStage = cursor.getColumnIndex(COL_BATCH_SYNC_STAGE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                ?.takeIf { it.isNotBlank() } ?: if (isSynced) RecordingSyncStage.UPLOADED.wireValue else RecordingSyncStage.PENDING.wireValue,
            retryCount = cursor.getColumnIndex(COL_BATCH_RETRY_COUNT).takeIf { it >= 0 }?.let(cursor::getInt) ?: 0,
            lastAttemptAtMillis = cursor.getColumnIndex(COL_BATCH_LAST_ATTEMPT_AT).takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L,
            lastError = cursor.getColumnIndex(COL_BATCH_LAST_ERROR).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            conflictCode = if (conflictCodeIdx >= 0 && !cursor.isNull(conflictCodeIdx)) cursor.getInt(conflictCodeIdx) else null,
            conflictReason = cursor.getColumnIndex(COL_BATCH_CONFLICT_REASON).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            inFlightAtMillis = cursor.getColumnIndex(COL_BATCH_IN_FLIGHT_AT).takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L,
            createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BATCH_CREATED_AT))
        )
    }

    private fun cursorToNotification(cursor: Cursor): NotificationItem {
        val avgBpmIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_AVG_BPM)
        val durationIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_DURATION_SECONDS)
        return NotificationItem(
            notificationId = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTIF_ID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTIF_TITLE)),
            messagePreview = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTIF_MESSAGE)),
            timestampLabel = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTIF_TIMESTAMP_LABEL)),
            isUnread = cursor.getInt(cursor.getColumnIndexOrThrow(COL_NOTIF_IS_UNREAD)) == 1,
            iconRes = cursor.getInt(cursor.getColumnIndexOrThrow(COL_NOTIF_ICON_RES)),
            sessionId = cursor.getColumnIndex(COL_NOTIF_SESSION_ID).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            sessionTimestampLabel = cursor.getColumnIndex(COL_NOTIF_SESSION_TS_LABEL).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            avgBpm = if (!cursor.isNull(avgBpmIdx)) cursor.getInt(avgBpmIdx) else null,
            durationSeconds = if (!cursor.isNull(durationIdx)) cursor.getInt(durationIdx) else null,
            startTimeUtc = cursor.getColumnIndex(COL_NOTIF_START_TIME_UTC).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            signalQuality = cursor.getColumnIndex(COL_NOTIF_SIGNAL_QUALITY).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            sessionStatus = cursor.getColumnIndex(COL_NOTIF_SESSION_STATUS).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            rhythmStatus = cursor.getColumnIndex(COL_NOTIF_RHYTHM_STATUS).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        )
    }
}
