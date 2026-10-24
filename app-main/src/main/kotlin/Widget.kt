package com.familia.tarefas

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.familia.core.sync.Graph
import java.time.Instant
import java.time.LocalDate
import java.time.Duration

data class WRow(val title: String, val who: String, val state: String)
data class WidgetData(
    val loggedIn: Boolean, val today: List<WRow>, val tomorrow: List<WRow>, val overdue: Int,
    val bread: String?, val balance: String?, val stale: String?,
)

object WidgetLoader {
    /** Lê SOMENTE o banco local: funciona sem internet. */
    suspend fun load(): WidgetData {
        val st = Graph.session.state.value
        if (!st.loggedIn || !st.ready) return WidgetData(false, emptyList(), emptyList(), 0, null, null, null)
        val dao = Graph.db.dao()
        val me = st.meId
        val today = LocalDate.now(); val tomorrow = today.plusDays(1)
        val tasks = dao.tasksOnce().associateBy { it.id }
        val members = dao.membersOnce().associate { it.id to it.displayName }
        val occ = dao.occurrencesBetween(today.minusDays(14).toString(), tomorrow.toString())
        val pendingCount = dao.pendingCount()
        fun row(o: com.familia.core.data.OccE): WRow {
            val t = tasks[o.taskId]
            val title = (t?.emoji?.takeIf { it.isNotBlank() }?.let { "$it " } ?: "") + (t?.name ?: "Tarefa") + (if (o.slotName.isNotBlank()) " · ${o.slotName}" else "")
            val who = if (o.effectiveMemberId == null) "Livre" else if (o.effectiveMemberId == me) "Você" else (members[o.effectiveMemberId] ?: "—")
            val state = when (o.status) { "done" -> "feita"; "awaiting" -> "aguardando"; "skipped" -> "dispensada"; else -> "pendente" }
            return WRow(title, who, state)
        }
        val sub = st.meRole == "subadmin"
        val apps = dao.appliancesOnce()
        val open = apps.firstOrNull { it.status != "none" && it.status != "confirmed" }
        val bal = dao.balancesOnce().filter { it.memberId == me && it.balance != 0 }
        val last = dao.kv("lastSyncAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val old = last == null || Duration.between(last, Instant.now()).toHours() >= 6
        val awaiting = occ.count { it.status == "awaiting" } + apps.count { it.status == "awaiting" }
        return WidgetData(
            true, occ.filter { it.date == today.toString() && it.status != "skipped" }.map(::row), occ.filter { it.date == tomorrow.toString() && it.status != "skipped" }.map(::row),
            if (sub) awaiting else occ.count { it.date < today.toString() && it.status == "pending" && it.effectiveMemberId == me },
            open?.let { (it.partName.ifBlank { "Por uso" }) + ": " + (com.familia.core.ui.StatusLabels[it.status] ?: it.status) + " · vez de " + (members[it.responsibleId ?: it.nextResponsibleId] ?: "—") },
            if (bal.isEmpty() || sub) null else bal.joinToString(" · ") { (if (it.balance > 0) "+" else "") + it.balance + " " + (tasks[it.taskId]?.name?.take(10) ?: "") },
            if (pendingCount > 0) "$pendingCount pendente(s) de envio" else if (old) "Dados desatualizados" else null,
        )
    }
}

class FamiliaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { WidgetLoader.load() }.getOrElse { WidgetData(false, emptyList(), emptyList(), 0, null, null, null) }
        provideContent { GlanceTheme { WidgetContent(data) } }
    }
}

class FamiliaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FamiliaWidget()
}

@Composable
private fun line(text: String, bold: Boolean = false, route: String = "tasks", size: Int = 13) {
    val ctx = LocalContext.current
    val open = actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("route", route))
    Text(
        text, maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        modifier = GlanceModifier.fillMaxWidth().clickable(open),
    )
}

@Composable
private fun WidgetContent(d: WidgetData) {
    val size = LocalSize.current
    val level = if (size.height >= 200.dp) 2 else if (size.width >= 200.dp) 1 else 0
    Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(10.dp)) {
        if (!d.loggedIn) { line("Casa em dia", true, "home"); line("Abra o app para configurar", route = "home"); return@Column }
        when (level) {
            0 -> { // compacto: tarefa relevante do dia, responsável e estado
                val r = d.today.firstOrNull { it.state == "pendente" } ?: d.today.firstOrNull()
                line("Hoje", true, "home")
                if (r == null) line("Nada hoje") else { line(r.title); line(r.who); line(r.state, true) }
            }
            1 -> { // médio: hoje, pendências, saldos
                line("Hoje", true, "home")
                d.today.forEach { line("${it.title}: ${it.who} (${it.state})") }
                if (d.overdue > 0) line("${d.overdue} atrasada(s) / aguardando", true)
                d.balance?.let { line("Saldo: $it") }
            }
            else -> { // expandido
                line("Hoje", true, "home")
                d.today.forEach { line("${it.title}: ${it.who} (${it.state})") }
                line("Amanhã", true, "calendar")
                d.tomorrow.forEach { line("${it.title}: ${it.who}", route = "calendar") }
                if (d.overdue > 0) line("${d.overdue} pendência(s) atrasada(s) / aguardando", true)
                d.bread?.let { line(it, route = "tasks") }
                d.balance?.let { line("Saldo: $it", route = "more") }
            }
        }
        d.stale?.let { line(it, route = "home", size = 11) }
    }
}
