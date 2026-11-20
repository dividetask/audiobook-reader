package com.dividetask.audiobookreader.libro

import com.dividetask.audiobookreader.data.db.dao.LibroBookDao
import com.dividetask.audiobookreader.data.db.entity.LibroBookEntity
import com.dividetask.audiobookreader.debug.AppDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The Libro.fm library: sign-in and a local copy of the purchased books. */
@Singleton
class LibroRepository @Inject constructor(
    private val api: LibroApi,
    private val account: LibroAccount,
    private val dao: LibroBookDao,
    private val debugLog: AppDebugLog
) {
    val books: Flow<List<LibroBookEntity>> = dao.getAll()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var lastSync = 0L

    /** Signs in and fetches the library. Throws with a readable message on failure. */
    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        debugLog.section("Libro.fm sign-in")
        val token = api.login(email.trim(), password)
        account.save(email.trim(), token)
        debugLog.log("LIBRO", "Signed in")
        sync(force = true)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        account.signOut()
        dao.deleteAll()
        debugLog.log("LIBRO", "Signed out")
    }

    /** Fetches the library; at most every 5 minutes unless [force]. Returns an error message or null. */
    suspend fun sync(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!account.isSignedIn || _syncing.value) return@withContext null
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 300_000) return@withContext null
        _syncing.value = true
        try {
            debugLog.section("Libro.fm library")
            val books = api.library()
            dao.insertAll(books)
            dao.deleteNotIn(books.map { it.isbn })
            lastSync = now
            debugLog.log("LIBRO", "Library: ${books.size} books")
            null
        } catch (e: LibroAuthException) {
            debugLog.log("LIBRO", "Library failed: ${e.message}")
            account.signOut()
            e.message
        } catch (e: Exception) {
            debugLog.log("LIBRO", "Library failed: ${e::class.simpleName}: ${e.message}")
            e.message ?: "Could not reach Libro.fm"
        } finally {
            _syncing.value = false
        }
    }

    suspend fun allOnce(): List<LibroBookEntity> = dao.getAllOnce()
}
