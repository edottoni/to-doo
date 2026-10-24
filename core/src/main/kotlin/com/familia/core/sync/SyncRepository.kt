package com.familia.core.sync

import android.content.Context
import com.familia.core.data.AppDb
import com.familia.core.data.KvE
import com.familia.core.data.OpDto
import com.familia.core.data.OpE
import com.familia.core.net.ApiClient
import com.familia.core.net.ApiError
import com.familia.core.net.SessionExpired
import com.familia.core.net.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.util.UUID

enum class SyncOutcome { OK, OFFLINE, AUTH, ERROR }

class SyncRepository(val db: AppDb, val api: ApiClient, val session: SessionStore) {
    private val mutex = Mutex()
    val dao get() = db.dao()
    /** Chamado após qualquer mudança local/sincronização (atualiza widget). */
    var onDataChanged: (suspend () -> Unit)? = null

    /** Registra a operação na fila persistente. NÃO altera os dados oficiais locais. */
    suspend fun enqueue(type: String, payload: JsonObject, targetKey: String, baseVersion: Int? = null): String {
        val id = UUID.randomUUID().toString()
        dao.insertOp(OpE(opId = id, type = type, payload = payload.toString(), baseVersion = baseVersion, clientTime = Instant.now().toString(), targetKey = targetKey))
        onDataChanged?.invoke()
        return id
    }
    suspend fun dismissOp(opId: String) { dao.deleteOp(opId); onDataChanged?.invoke() }

    /** Envia a fila (na ordem) e depois baixa o estado oficial. Seguro para chamar várias vezes. */
    suspend fun sync(): SyncOutcome = mutex.withLock {
        if (!session.state.value.loggedIn) return@withLock SyncOutcome.AUTH
        try {
            val pending = dao.pendingOps()
            if (pending.isNotEmpty()) {
                val resp = api.push(pending.map { OpDto(it.opId, it.type, Json.parseToJsonElement(it.payload), it.baseVersion, it.clientTime) })
                val byId = resp.results.associateBy { it.opId }
                for (op in pending) {
                    val r = byId[op.opId] ?: continue
                    when (r.status) {
                        "applied", "duplicate" -> dao.deleteOp(op.opId)
                        "conflict" -> dao.markOp(op.opId, "conflict", r.message ?: r.code)
                        else -> if (r.code != "server_error") dao.markOp(op.opId, "rejected", r.message ?: r.code)
                    }
                }
            }
            val since = dao.kv("serverVersion")?.toLongOrNull() ?: -1L
            val snap = api.pull(since)
            if (!snap.unchanged) dao.replaceAll(snap)
            dao.putKv(KvE("lastSyncAt", Instant.now().toString()))
            dao.putKv(KvE("lastError", ""))
            session.markReady()
            onDataChanged?.invoke()
            SyncOutcome.OK
        } catch (e: SessionExpired) {
            dao.putKv(KvE("lastError", "Sessão expirada")); SyncOutcome.AUTH
        } catch (e: IOException) {
            dao.putKv(KvE("lastError", "Sem conexão")); SyncOutcome.OFFLINE
        } catch (e: ApiError) {
            dao.putKv(KvE("lastError", e.message ?: "Erro")); SyncOutcome.ERROR
        }
    }

    suspend fun logout() { dao.wipe(); session.logout(); onDataChanged?.invoke() }
    suspend fun forgetSite() { dao.wipe(); session.forgetSite(); onDataChanged?.invoke() }
}

object Graph {
    lateinit var appKind: String; private set
    lateinit var db: AppDb; private set
    lateinit var session: SessionStore; private set
    lateinit var api: ApiClient; private set
    lateinit var repo: SyncRepository; private set
    /** Gancho de cada app (notificações). */
    var afterSync: (suspend (Context, SyncOutcome) -> Unit)? = null

    @Synchronized
    fun init(ctx: Context, kind: String) {
        if (::repo.isInitialized) return
        val app = ctx.applicationContext
        appKind = kind
        session = SessionStore(app)
        db = AppDb.create(app, "casa-em-dia-$kind.db")
        api = ApiClient(session, kind)
        repo = SyncRepository(db, api, session)
    }
}
