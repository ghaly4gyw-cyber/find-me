package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.mesh.ProfileStore
import com.ghaly.findme.repo.LedgerRepository
import com.ghaly.findme.repo.NotesRepository
import com.ghaly.findme.repo.OnlineContactsRepository
import com.ghaly.findme.repo.SavedPlaceRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot (not opportunistic/throttled like the Uploaders) call to
 * reclaim.php — "if he registered to a new phone, he will be auto required
 * to be logged out from the other one". Needs a live connection by its very
 * nature (there's no offline way to prove you're the same person on a new
 * phone), so this is only ever triggered by someone explicitly tapping
 * "Recover account" and deliberately waiting for a real answer, never run
 * silently in the background. On success, restores everything the old phone
 * had (profile fields + the accounts ledger/notes/saved places bundle — see
 * BackupUploader) directly into this phone's own repositories.
 */
object AccountReclaimer {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/reclaim.php"

    sealed class Result {
        data class Success(val name: String, val phone: String) : Result()
        object NoSuchAccount : Result()
        object NoPinSet : Result()
        data class WrongPin(val attemptsRemaining: Int) : Result()
        data class Locked(val retryAfterSeconds: Int) : Result()
        object DeviceAlreadyBoundElsewhere : Result()
        object NetworkError : Result()
    }

    /** Call off the main thread. */
    fun reclaim(context: Context, phone: String, pin: String, deviceId: String): Result {
        try {
            val body = JSONObject()
                .put("phone", phone)
                .put("pin", pin)
                .put("device_id", deviceId)
            val connection = (URL(ENDPOINT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return Result.NetworkError
            val json = JSONObject(text)

            if (responseCode in 200..299 && json.optString("status") == "ok") {
                val name = json.optString("name", "")
                val description = json.optString("description", "").ifEmpty { null }
                val photoBase64 = json.optString("photo_base64", "").ifEmpty { null }
                ProfileStore.save(context, phone = phone, description = description, photoBase64 = photoBase64)
                if (name.isNotEmpty()) {
                    com.ghaly.findme.mesh.DeviceIdentity.setName(context, name)
                }
                ProfileStore.markRegistered(context)
                ProfileStore.markPinSetFromReclaim(context)
                restoreBackup(json.optString("backup_json", "").ifEmpty { null })
                return Result.Success(name, phone)
            }

            return when (json.optString("reason")) {
                "no_such_account" -> Result.NoSuchAccount
                "no_pin_set" -> Result.NoPinSet
                "wrong_pin" -> Result.WrongPin(json.optInt("attempts_remaining", 0))
                "locked" -> Result.Locked(json.optInt("retry_after_seconds", 900))
                "device_already_registered_to_another_number" -> Result.DeviceAlreadyBoundElsewhere
                else -> Result.NetworkError
            }
        } catch (e: Exception) {
            return Result.NetworkError
        }
    }

    /** [rawBundle] is the JSON object AccountBackupUploader built — null or
     *  blank just means this account never backed up anything yet (e.g. it
     *  was reclaimed moments after being created), which is a normal, fine
     *  outcome, not an error. */
    private fun restoreBackup(rawBundle: String?) {
        if (rawBundle.isNullOrBlank()) return
        try {
            val bundle = JSONObject(rawBundle)
            LedgerRepository.restoreFromBackup(
                bundle.optJSONArray("ledgerBooks")?.toString() ?: "[]",
                bundle.optJSONArray("ledgerAccounts")?.toString() ?: "[]"
            )
            NotesRepository.restoreFromBackup(bundle.optJSONArray("notes")?.toString() ?: "[]")
            SavedPlaceRepository.restoreFromBackup(bundle.optJSONArray("places")?.toString() ?: "[]")
            OnlineContactsRepository.restoreFromBackup(bundle.optJSONArray("onlineContacts")?.toString() ?: "[]")
        } catch (e: Exception) {
            // A corrupt/old-format backup shouldn't block the reclaim itself
            // (the account/profile side already succeeded) — worst case the
            // ledger/notes/places just stay empty on this phone, same as any
            // fresh registration.
        }
    }
}
