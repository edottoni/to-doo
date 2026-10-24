package com.familia.tarefas

import android.content.Context
import com.familia.core.sync.Graph
import com.familia.core.sync.Notify
import com.familia.core.sync.SyncOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalTime

val NOTIF_CATEGORIES = listOf(
    "daily" to "Lembrete do dia (minhas tarefas de hoje)", "pending" to "Tarefa atrasada",
    "swap_in" to "Pedido de troca recebido", "vote_result" to "Resultado de pedido de troca",
    "comp" to "Saldo alterado", "schedule" to "Mudança no meu dia",
    "appliance" to "Tarefa por uso: é a minha vez", "confirm_result" to "Confirmação aceita ou rejeitada",
    "awaiting" to "Aguardando a minha confirmação (subadmin)", "sync_fail" to "Falha de sincronização",
)

/** Roda após cada sincronização em segundo plano. Cada evento notifica uma única vez (deduplicado). */
object MainNotifier {
    suspend fun check(ctx: Context, outcome: SyncOutcome) {
        val st = Graph.session.state.value; val me = st.meId ?: return
        val dao = Graph.db.dao()
        val first = !Notify.isSeeded(ctx)
        Notify.silent = first
        try {
            val today = LocalDate.now().toString()
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            val tasks = dao.tasksOnce().associateBy { it.id }
            val all = dao.occurrencesBetween(LocalDate.now().minusDays(14).toString(), today)
            if (st.meRole == "subadmin") {
                for (o in all.filter { it.status == "awaiting" })
                    Notify.fire(ctx, MainActivity::class.java, "awaiting", "aw:${o.taskId}|${o.key}:${o.version}", "Aguardando confirmação", "${tasks[o.taskId]?.name ?: "Tarefa"} de ${o.date} espera a sua decisão.")
                for (a in dao.appliancesOnce().filter { it.status == "awaiting" })
                    Notify.fire(ctx, MainActivity::class.java, "awaiting", "awc:${a.cycleId}:${a.version}", "Aguardando confirmação", "${a.partName.ifBlank { "Uma limpeza" }} espera a sua decisão.")
            } else {
                for (o in all.filter { it.effectiveMemberId == me && it.status == "pending" }) {
                    val name = tasks[o.taskId]?.name ?: "Tarefa"
                    if (o.date == today) {
                        if (now >= Notify.reminderMinutes(ctx, "daily", 19 * 60)) Notify.fire(ctx, MainActivity::class.java, "daily", "daily:${o.taskId}:${o.key}", name, "Hoje é a sua vez.")
                    } else if (o.date < today) Notify.fire(ctx, MainActivity::class.java, "pending", "pending:${o.taskId}:${o.key}:$today", "Tarefa atrasada", "$name de ${o.date} ainda não foi concluída.")
                }
                for (o in all.filter { it.status == "pending" && it.rejectReason != null && it.effectiveMemberId == me })
                    Notify.fire(ctx, MainActivity::class.java, "confirm_result", "rej:${o.taskId}|${o.key}:${o.version}", "Confirmação rejeitada", o.rejectReason ?: "Revise a tarefa.")
                for (p in dao.proposalsOnce()) {
                    val missing = runCatching { Json.parseToJsonElement(p.consensus).jsonObject["missing"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
                    val voted = runCatching { Json.parseToJsonElement(p.votes).jsonArray.map { it.jsonObject["memberId"]!!.jsonPrimitive.content } }.getOrDefault(emptyList())
                    if (p.status == "waiting" && p.proposerId != me && me in missing) Notify.fire(ctx, MainActivity::class.java, "swap_in", "swap:${p.id}", "Pedido de troca", "Há um pedido aguardando o seu voto.")
                    if ((p.status == "applied" || p.status == "rejected") && (p.proposerId == me || me in voted))
                        Notify.fire(ctx, MainActivity::class.java, "vote_result", "vote:${p.id}:${p.status}", "Troca ${if (p.status == "applied") "aplicada" else "não aprovada"}", "Veja os detalhes em Trocas.")
                    if (p.status == "applied" && p.payload.contains(me)) Notify.fire(ctx, MainActivity::class.java, "schedule", "sched:${p.id}", "Seu dia mudou", "Uma troca aprovada afeta os seus dias.")
                }
                for (l in dao.ledgerOnce()) if (l.memberId == me && l.status == "active")
                    Notify.fire(ctx, MainActivity::class.java, "comp", "led:${l.id}", "Saldo alterado", "Você ${if (l.delta > 0) "ganhou" else "perdeu"} ${kotlin.math.abs(l.delta)} no saldo.")
                for (a in dao.appliancesOnce()) {
                    if ((a.status == "needed" || a.status == "rejected") && a.responsibleId == me)
                        Notify.fire(ctx, MainActivity::class.java, "appliance", "ap:${a.cycleId}:${a.version}", a.partName.ifBlank { "Tarefa por uso" }, if (a.status == "rejected") (a.rejectReason ?: "Revise a limpeza.") else "A vez de limpar é sua.")
                }
            }
            if (outcome == SyncOutcome.ERROR) Notify.fire(ctx, MainActivity::class.java, "sync_fail", "sync:$today", "Falha de sincronização", "O site respondeu com erro. Abra o app para ver os detalhes.")
        } finally {
            Notify.silent = false
            if (first) Notify.markSeeded(ctx)
        }
    }
}
