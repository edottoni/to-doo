package com.familia.tarefas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.familia.core.data.*
import com.familia.core.sync.Graph
import com.familia.core.sync.Notify
import com.familia.core.sync.SyncScheduler
import com.familia.core.ui.parseColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

class MainVm(app: Application) : AndroidViewModel(app) {
    private val dao = Graph.db.dao()
    private val repo = Graph.repo
    private fun <T> Flow<T>.st(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    val me = Graph.session.state
    val members = dao.members().st(emptyList())
    val tasks = dao.tasks().st(emptyList())
    val occ = dao.occurrences().st(emptyList())
    val appliances = dao.appliances().st(emptyList())
    val checklist = dao.checklist().st(emptyList())
    val ledger = dao.ledger().st(emptyList())
    val balances = dao.balances().st(emptyList())
    val proposals = dao.proposals().st(emptyList())
    val confirmations = dao.confirmations().st(emptyList())
    val ops = dao.ops().st(emptyList())
    val lastSync = dao.kvFlow("lastSyncAt").st(null)
    val serverUpdated = dao.kvFlow("serverUpdatedAt").st(null)
    val serverVersion = dao.kvFlow("serverVersion").st(null)
    val lastError = dao.kvFlow("lastError").st(null)
    val syncing = MutableStateFlow(false)

    fun name(id: String?): String = if (id == null) "Livre" else members.value.firstOrNull { it.id == id }?.displayName ?: "—"
    fun color(id: String?): Color = if (id == null) Color(0xFF8A98A5) else parseColor(members.value.firstOrNull { it.id == id }?.color)
    fun task(id: String): TaskE? = tasks.value.firstOrNull { it.id == id }
    fun taskName(id: String): String = task(id)?.let { (if (it.emoji.isNotBlank()) it.emoji + " " else "") + it.name } ?: ""
    fun ids(csv: String): List<String> = csv.split(",").filter { it.isNotBlank() }

    fun sync() { viewModelScope.launch { syncing.value = true; repo.sync(); syncing.value = false } }
    private fun enqueue(type: String, payload: JsonObject, target: String, base: Int? = null) {
        viewModelScope.launch { repo.enqueue(type, payload, target, base); SyncScheduler.syncNow(getApplication()) }
    }

    // ---- usuário / admin ----
    fun complete(o: OccE) = enqueue("occurrence.complete", buildJsonObject { put("taskId", o.taskId); put("key", o.key) }, "${o.taskId}|${o.key}")
    fun useEvent(taskId: String) = enqueue("event.use", buildJsonObject { put("taskId", taskId); put("usedAt", Instant.now().toString()) }, "use:$taskId")
    fun toggle(cycleId: String, itemId: String, done: Boolean) =
        enqueue("cleaning.toggle", buildJsonObject { put("cycleId", cycleId); put("itemId", itemId); put("done", done) }, "item:$itemId")
    fun submit(cycleId: String) = enqueue("cleaning.submit", buildJsonObject { put("cycleId", cycleId) }, "submit:$cycleId")
    fun vote(proposalId: String, vote: String) = enqueue("proposal.vote", buildJsonObject { put("proposalId", proposalId); put("vote", vote) }, "vote:$proposalId")
    fun propose(taskId: String, kind: String, items: List<Triple<String, String, String>>, targetKey: String) {
        val payload = buildJsonObject {
            put("taskId", taskId); put("kind", kind)
            put("items", buildJsonArray { items.forEach { (k, f, t) -> add(buildJsonObject { put("key", k); put("fromMemberId", f); put("toMemberId", t) }) } })
        }
        enqueue("proposal.create", payload, "propose:$targetKey")
    }

    // ---- subadmin (confirmador) ----
    fun confirmOcc(o: OccE, accept: Boolean, reason: String?) = enqueue("occurrence.confirm", buildJsonObject {
        put("taskId", o.taskId); put("key", o.key); put("decision", if (accept) "accept" else "reject"); if (!reason.isNullOrBlank()) put("reason", reason)
    }, "confirm:${o.taskId}|${o.key}", o.version)
    fun markDoneOcc(o: OccE, performedBy: String?) = enqueue("occurrence.markDone", buildJsonObject {
        put("taskId", o.taskId); put("key", o.key); if (!performedBy.isNullOrBlank()) put("performedBy", performedBy)
    }, "markdone:${o.taskId}|${o.key}")
    fun confirmCycle(a: ApplianceE, accept: Boolean, reason: String?) {
        val id = a.cycleId ?: return
        enqueue("cleaning.confirm", buildJsonObject { put("cycleId", id); put("decision", if (accept) "accept" else "reject"); if (!reason.isNullOrBlank()) put("reason", reason) }, "confirm:$id", a.version)
    }
    fun markWashed(taskId: String, isoDate: String) =
        enqueue("cleaning.markWashed", buildJsonObject { put("taskId", taskId); put("washedOn", isoDate) }, "washed:$taskId")

    fun dismiss(opId: String) { viewModelScope.launch { repo.dismissOp(opId) } }
    fun discardAndRefresh(opId: String) { viewModelScope.launch { repo.dismissOp(opId); syncing.value = true; repo.sync(); syncing.value = false } }
    fun logout() { viewModelScope.launch { repo.logout(); Notify.clearHistory(getApplication()) } }

    /** Valor efetivo do checklist: último toggle pendente (local) ou o valor oficial do servidor. */
    fun effectiveDone(item: ChecklistE, ops: List<OpE>): Boolean {
        val last = ops.lastOrNull { it.state == "pending" && it.type == "cleaning.toggle" && runCatching { Json.parseToJsonElement(it.payload).jsonObject["itemId"]?.jsonPrimitive?.content == item.itemId }.getOrDefault(false) }
        return last?.let { runCatching { Json.parseToJsonElement(it.payload).jsonObject["done"]?.jsonPrimitive?.content == "true" }.getOrDefault(item.done) } ?: item.done
    }
}
