@file:Suppress("SpellCheckingInspection")

package com.example.scamguard

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class CallerReputation(
    val phoneNumber: String,
    val identifiedName: String,
    val category: String,
    val reportCount: Int,
    val riskLevel: RiskLevel,
    val isBlocked: Boolean
)

data class FavoriteContact(
    val name: String,
    val number: String,
    val normalizedNumber: String
)

data class CallRecordingItem(
    val id: String,
    val file: File,
    val callerName: String,
    val phoneNumber: String,
    val timestampMs: Long,
    val durationMs: Long
) {
    val durationSec: Long
        get() = max(1L, (durationMs + 500L) / 1000L)
}

enum class RiskLevel {
    SAFE, SUSPICIOUS, CRITICAL_SCAM
}

object ScamIntelligenceRepo {

    private const val PREFS_NAME = "scamguard_truecaller_db"
    private const val KEY_REPORTS = "community_reports_json"
    private const val KEY_BLOCKED = "blocked_numbers_set"
    private const val KEY_FAVORITES = "favorite_contacts_json"
    private const val KEY_UNSTARRED = "excluded_system_starred_set"
    private const val KEY_RECORDINGS_META = "call_recordings_metadata_json"

    private val defaultDirectory = mapOf(
        "9876543210" to CallerReputation("9876543210", "Fake CBI Officer (Digital Arrest)", "Authority Impersonation", 1428, RiskLevel.CRITICAL_SCAM, false),
        "7935417825" to CallerReputation("7935417825", "FedEx Customs Parcel Scam", "Courier Scam", 389, RiskLevel.CRITICAL_SCAM, false),
        "9999888877" to CallerReputation("9999888877", "KYC Bank Account Freeze", "OTP Phishing", 912, RiskLevel.CRITICAL_SCAM, false),
        "8888777766" to CallerReputation("8888777766", "KSEB Bill Disconnect Fraud", "Utility Scam", 540, RiskLevel.CRITICAL_SCAM, false),
        "18004251122" to CallerReputation("18004251122", "KSEB Official Customer Care", "Verified Utility", 0, RiskLevel.SAFE, false)
    )

    fun normalizeNumber(raw: String): String {
        val digitsOnly = raw.filter { it.isDigit() }
        return when {
            digitsOnly.length > 10 -> digitsOnly.takeLast(10)
            else -> digitsOnly
        }
    }

    fun lookupNumber(context: Context, rawNumber: String): CallerReputation {
        val clean = normalizeNumber(rawNumber)
        val blockedSet = getBlockedNumbers(context)
        val isBlocked = blockedSet.contains(clean)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_REPORTS, "{}") ?: "{}"
        val root = JSONObject(jsonStr)

        if (root.has(clean)) {
            val item = root.getJSONObject(clean)
            val count = item.optInt("count", 1)
            val risk = if (count >= 10) RiskLevel.CRITICAL_SCAM else RiskLevel.SUSPICIOUS
            return CallerReputation(
                phoneNumber = rawNumber,
                identifiedName = item.optString("name", "Suspected Spammer"),
                category = item.optString("category", "Community Flagged"),
                reportCount = count,
                riskLevel = risk,
                isBlocked = isBlocked
            )
        }

        defaultDirectory[clean]?.let { preset ->
            return preset.copy(isBlocked = isBlocked)
        }

        if (clean.startsWith("140") || rawNumber.contains("+91140")) {
            return CallerReputation(
                phoneNumber = rawNumber,
                identifiedName = "Promotional Telemarketer",
                category = "Automated Marketing Call",
                reportCount = 96,
                riskLevel = RiskLevel.SUSPICIOUS,
                isBlocked = isBlocked
            )
        }
        if (clean.startsWith("160") || rawNumber.contains("+91160")) {
            return CallerReputation(
                phoneNumber = rawNumber,
                identifiedName = "Transactional Service Line",
                category = "Service Call",
                reportCount = 0,
                riskLevel = RiskLevel.SAFE,
                isBlocked = isBlocked
            )
        }

        return CallerReputation(
            phoneNumber = rawNumber,
            identifiedName = "Unknown",
            category = "Unverified Number",
            reportCount = 0,
            riskLevel = RiskLevel.SAFE,
            isBlocked = isBlocked
        )
    }

    fun reportAndTagNumber(
        context: Context,
        rawNumber: String,
        callerTagName: String,
        scamCategory: String,
        alsoBlock: Boolean
    ) {
        val clean = normalizeNumber(rawNumber)
        if (clean.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_REPORTS, "{}") ?: "{}"
        val root = JSONObject(jsonStr)

        val currentCount = if (root.has(clean)) {
            root.getJSONObject(clean).optInt("count", 15) + 1
        } else {
            defaultDirectory[clean]?.reportCount?.plus(1) ?: 18
        }

        val entry = JSONObject().apply {
            put("name", callerTagName.ifBlank { "Reported Scammer" })
            put("category", scamCategory)
            put("count", currentCount)
        }
        root.put(clean, entry)
        prefs.edit().putString(KEY_REPORTS, root.toString()).apply()

        if (alsoBlock) {
            setBlockedState(context, rawNumber, true)
        }
    }

    fun setBlockedState(context: Context, rawNumber: String, block: Boolean) {
        val clean = normalizeNumber(rawNumber)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_BLOCKED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (block) set.add(clean) else set.remove(clean)
        prefs.edit().putStringSet(KEY_BLOCKED, set).apply()
    }

    fun getBlockedNumbers(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
    }

    // =========================================================================
    // FAVORITES PERSISTENCE ENGINE (NO DUPLICATES)
    // =========================================================================
    fun getFavorites(context: Context): List<FavoriteContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        val list = mutableListOf<FavoriteContact>()
        val seenNorm = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        try {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "Favorite").trim()
                val number = obj.optString("number", "").trim()
                val norm = normalizeNumber(number)
                val nameKey = name.lowercase()
                if (number.isNotBlank() && norm.isNotEmpty() &&
                    seenNorm.add(norm) && seenNames.add(nameKey)
                ) {
                    list.add(FavoriteContact(name, number, norm))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun getExcludedSystemStarred(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_UNSTARRED, emptySet()) ?: emptySet()
    }

    fun isFavorite(context: Context, rawNumber: String): Boolean {
        val clean = normalizeNumber(rawNumber)
        if (clean.isEmpty()) return false
        return getFavorites(context).any { it.normalizedNumber == clean }
    }

    fun setFavoriteState(context: Context, name: String, rawNumber: String, makeFavorite: Boolean) {
        val clean = normalizeNumber(rawNumber)
        if (clean.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val excludedSet = getExcludedSystemStarred(context).toMutableSet()
        val current = getFavorites(context).toMutableList()

        current.removeAll {
            it.normalizedNumber == clean || it.name.equals(name.trim(), ignoreCase = true)
        }
        if (makeFavorite) {
            current.add(0, FavoriteContact(name.ifBlank { rawNumber }, rawNumber, clean))
            excludedSet.remove(clean)
        } else {
            excludedSet.add(clean)
        }

        prefs.edit().putStringSet(KEY_UNSTARRED, excludedSet).apply()
        saveFavoritesList(context, current)
    }

    private fun saveFavoritesList(context: Context, list: List<FavoriteContact>) {
        val arr = JSONArray()
        val seen = mutableSetOf<String>()
        for (fav in list) {
            if (seen.add(fav.normalizedNumber)) {
                val obj = JSONObject().apply {
                    put("name", fav.name)
                    put("number", fav.number)
                    put("norm", fav.normalizedNumber)
                }
                arr.put(obj)
            }
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    // =========================================================================
    // CALL RECORDINGS STORAGE & METADATA ENGINE
    // =========================================================================
    fun getRecordingsDir(context: Context): File {
        val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        val dir = File(musicRoot, "CallRecordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getLegacyRecordingsDir(context: Context): File {
        return File(context.getExternalFilesDir(null), "CallRecordings")
    }

    fun saveRecordingMetadata(
        context: Context,
        file: File,
        callerName: String,
        phoneNumber: String,
        timestampMs: Long,
        durationMs: Long
    ) {
        if (!file.exists() || file.length() <= 256L) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDINGS_META, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val updated = JSONArray()
        val canonicalPath = file.absolutePath

        val newEntry = JSONObject().apply {
            put("id", file.name)
            put("filePath", canonicalPath)
            put("callerName", callerName.ifBlank { phoneNumber })
            put("phoneNumber", phoneNumber)
            put("timestampMs", timestampMs)
            put("durationMs", max(1000L, durationMs))
        }
        updated.put(newEntry)

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val existingPath = obj.optString("filePath", "")
            if (existingPath.isNotBlank() && existingPath != canonicalPath && File(existingPath).exists()) {
                updated.put(obj)
            }
        }

        prefs.edit().putString(KEY_RECORDINGS_META, updated.toString()).apply()
    }

    fun deleteRecording(context: Context, file: File): Boolean {
        val targetPath = file.absolutePath
        var deletedFile = false
        try {
            if (file.exists()) {
                deletedFile = file.delete()
            } else {
                deletedFile = true
            }
        } catch (_: Exception) {}

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDINGS_META, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            val updated = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("filePath") != targetPath &&
                    obj.optString("id") != file.name
                ) {
                    updated.put(obj)
                }
            }
            prefs.edit().putString(KEY_RECORDINGS_META, updated.toString()).apply()
        } catch (_: Exception) {}

        return deletedFile
    }

    fun extractAudioDurationMs(file: File): Long {
        if (!file.exists() || file.length() <= 256L) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durStr?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    fun getSavedRecordings(context: Context): List<CallRecordingItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDINGS_META, "[]") ?: "[]"

        val results = mutableListOf<CallRecordingItem>()
        val seenPaths = mutableSetOf<String>()

        // 1. Load persisted metadata entries and verify each audio file exists
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("filePath", "")
                if (path.isBlank()) continue
                val file = File(path)
                if (!file.exists() || !file.isFile || file.length() <= 256L) continue

                if (seenPaths.add(file.absolutePath)) {
                    val phone = obj.optString("phoneNumber", "Unknown")
                    val freshContactName = ScamGuardInCallService.getContactName(context, phone)
                        ?: obj.optString("callerName", phone)
                    val savedDurMs = obj.optLong("durationMs", 0L)
                    val verifiedDurMs = if (savedDurMs > 0L) savedDurMs else extractAudioDurationMs(file)

                    results.add(
                        CallRecordingItem(
                            id = obj.optString("id", file.name),
                            file = file,
                            callerName = freshContactName,
                            phoneNumber = phone,
                            timestampMs = obj.optLong("timestampMs", file.lastModified()),
                            durationMs = max(1000L, verifiedDurMs)
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Also scan both primary and legacy CallRecordings directories so existing files are linked
        val dirsToScan = listOf(getRecordingsDir(context), getLegacyRecordingsDir(context))
        for (dir in dirsToScan) {
            if (!dir.exists()) continue
            val files = dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".m4a") || f.name.endsWith(".3gp") || f.name.endsWith(".mp4")) &&
                        f.length() > 256L
            } ?: continue

            for (file in files) {
                if (!seenPaths.add(file.absolutePath)) continue
                val base = file.nameWithoutExtension
                val parts = base.split("_")
                val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: file.lastModified()
                val nameDurSec = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val rawNum = parts.drop(3).joinToString("").ifBlank { "Unknown" }
                val contactName = ScamGuardInCallService.getContactName(context, rawNum) ?: rawNum
                val probedDurMs = extractAudioDurationMs(file)
                val finalDurMs = when {
                    probedDurMs > 0L -> probedDurMs
                    nameDurSec > 0L -> nameDurSec * 1000L
                    else -> 1000L
                }

                val item = CallRecordingItem(
                    id = file.name,
                    file = file,
                    callerName = contactName,
                    phoneNumber = rawNum,
                    timestampMs = timestamp,
                    durationMs = finalDurMs
                )
                results.add(item)
                saveRecordingMetadata(
                    context = context,
                    file = file,
                    callerName = contactName,
                    phoneNumber = rawNum,
                    timestampMs = timestamp,
                    durationMs = finalDurMs
                )
            }
        }

        return results.sortedByDescending { it.timestampMs }
    }
}