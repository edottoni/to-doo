package com.familia.tarefas

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.familia.core.data.*
import com.familia.core.sync.Notify
import com.familia.core.sync.SyncScheduler
import com.familia.core.ui.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

internal val GREEN = Color(0xFF157347); internal val AMBER = Color(0xFF9A5B00); internal val BLUE = Color(0xFF2F5DA8); internal val GRAY = Color(0xFF5B6B76); internal val RED = Color(0xFFB42318)
private val TABS = listOf("home" to "Início", "calendar" to "Calendário", "tasks" to "Tarefas", "swaps" to "Trocas", "more" to "Mais")
private val ICON = mapOf("home" to "⌂", "calendar" to "▦", "tasks" to "✓", "swaps" to "⇄", "more" to "≡")
private val KIND_LABEL = mapOf("substitution" to "Passar o meu dia para outra pessoa", "swap" to "Trocar de dia com outra pessoa")

@Composable
internal fun WhoChip(vm: MainVm, id: String?) {
    val c = vm.color(id)
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp)); Text(vm.name(id), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun SyncBar(vm: MainVm) {
    val ops by vm.ops.collectAsState(); val last by vm.lastSync.collectAsState(); val err by vm.lastError.collectAsState(); val busy by vm.syncing.collectAsState()
    val pending = ops.count { it.state == "pending" }; val problems = ops.count { it.state != "pending" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sincronizado: ${Fmt.dateTime(last)}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.sync() }, enabled = !busy) { Text(if (busy) "Atualizando…" else "Atualizar") }
        }
        if (pending > 0) Pill("$pending alteração(ões) pendente(s) de envio — ainda não valem no servidor", AMBER)
        if (problems > 0) Pill("$problems alteração(ões) com conflito ou recusa — veja em Ajustes", RED)
        if (!err.isNullOrBlank()) Text("Última tentativa: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun OccRow(vm: MainVm, o: OccE, onSwap: (OccE) -> Unit) {
    val st by vm.me.collectAsState(); val ops by vm.ops.collectAsState(); val tasks by vm.tasks.collectAsState()
    val me = st.meId; val t = tasks.firstOrNull { it.id == o.taskId }
    val today = LocalDate.now().toString(); val key = "${o.taskId}|${o.key}"
    val queued = ops.any { it.state == "pending" && it.type == "occurrence.complete" && it.targetKey == key }
    val proposing = ops.any { it.state == "pending" && it.targetKey == "propose:$key" }
    val canDo = st.meRole == "admin" || (t != null && me != null && me in vm.ids(t.participants))
    val title = vm.taskName(o.taskId) + if (o.slotName.isNotBlank()) " · ${o.slotName}" else ""
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WhoChip(vm, o.effectiveMemberId)
            if (o.plannedMemberId != null && o.effectiveMemberId != o.plannedMemberId) Text("(padrão: ${vm.name(o.plannedMemberId)})", style = MaterialTheme.typography.bodySmall)
        }
        when {
            o.status == "done" -> Pill("Feito" + (o.completedBy?.let { " por ${vm.name(it)}" } ?: ""), GREEN)
            queued -> Pill("Registrado — pendente de envio", AMBER)
            o.status == "awaiting" -> Pill("Aguardando confirmação", BLUE)
            o.status == "skipped" -> Pill("Dispensado", GRAY)
            o.inReview || proposing -> Pill("Em análise", BLUE)
            else -> Pill(if (o.effectiveMemberId == null) "Livre" else "Pendente", AMBER)
        }
        o.rejectReason?.let { Text("Rejeitado: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        o.note?.let { Text("📝 $it", style = MaterialTheme.typography.bodySmall) }
        Row {
            if (o.status == "pending" && !queued && canDo && o.date <= today)
                TextButton({ vm.complete(o) }) { Text(when { o.effectiveMemberId == me -> "Concluir"; o.effectiveMemberId == null -> "Assumir e fazer"; else -> "Fiz no lugar de ${vm.name(o.effectiveMemberId)}" }) }
            if (o.status == "pending" && !queued && !proposing && !o.inReview && o.effectiveMemberId == me && o.date >= today)
                TextButton({ onSwap(o) }) { Text("Propor troca") }
        }
    }
}

@Composable
fun MainRoot(startRoute: String?) {
    val vm: MainVm = viewModel()
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("home") }
    var sub by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(startRoute) { if (startRoute != null && TABS.any { it.first == startRoute }) { tab = startRoute; sub = null } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
        SyncScheduler.syncNow(ctx)
    }
    Scaffold(bottomBar = {
        NavigationBar {
            TABS.forEach { (r, l) -> NavigationBarItem(selected = tab == r, onClick = { tab = r; sub = null }, icon = { Text(ICON[r] ?: "") }, label = { Text(l, maxLines = 1) }) }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                "home" -> HomeScreen(vm)
                "calendar" -> CalendarScreen(vm)
                "tasks" -> TasksScreen(vm)
                "swaps" -> SwapsScreen(vm)
                else -> when (sub) {
                    "balance" -> BalancesScreen(vm) { sub = null }
                    "history" -> HistoryScreen(vm) { sub = null }
                    "settings" -> SettingsScreen(vm) { sub = null }
                    else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("Mais", style = MaterialTheme.typography.headlineSmall) }
                        item { Button({ sub = "balance" }, Modifier.fillMaxWidth()) { Text("Saldos") } }
                        item { Button({ sub = "history" }, Modifier.fillMaxWidth()) { Text("Histórico") } }
                        item { Button({ sub = "settings" }, Modifier.fillMaxWidth()) { Text("Ajustes") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: MainVm) {
    val occ by vm.occ.collectAsState(); val apps by vm.appliances.collectAsState(); val bal by vm.balances.collectAsState(); val st by vm.me.collectAsState()
    val today = LocalDate.now().toString(); val tomorrow = LocalDate.now().plusDays(1).toString()
    var swap by remember { mutableStateOf<OccE?>(null) }
    val late = occ.filter { it.date < today && (it.status == "pending" || it.status == "awaiting") }
    val mine = bal.filter { it.memberId == st.meId }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Olá, ${st.meName ?: ""}", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        item { Section("Hoje · ${Fmt.day(today)}") { val l = occ.filter { it.date == today }; if (l.isEmpty()) Text("Nada para hoje.") else l.forEach { OccRow(vm, it) { o -> swap = o } } } }
        item { Section("Amanhã · ${Fmt.day(tomorrow)}") { val l = occ.filter { it.date == tomorrow }; if (l.isEmpty()) Text("Nada para amanhã.") else l.forEach { OccRow(vm, it) { o -> swap = o } } } }
        if (late.isNotEmpty()) item { Section("Atrasadas") { late.forEach { Text(Fmt.day(it.date), style = MaterialTheme.typography.labelMedium); OccRow(vm, it) { o -> swap = o } } } }
        val open = apps.filter { it.status != "none" && it.status != "confirmed" }
        if (open.isNotEmpty()) item { Section("Tarefas por uso") { open.forEach { a ->
            Text(a.partName.ifBlank { "Tarefa por uso" }, style = MaterialTheme.typography.bodyLarge)
            Pill(StatusLabels[a.status] ?: a.status, if (a.status == "awaiting") BLUE else AMBER)
            KeyValue("Vez de", vm.name(a.responsibleId ?: a.nextResponsibleId))
        } } }
        item { Section("Meu saldo") {
            if (mine.none { it.balance != 0 }) Text("Sem saldo no momento.") else mine.filter { it.balance != 0 }.forEach { KeyValue(vm.taskName(it.taskId), (if (it.balance > 0) "+" else "") + it.balance) }
            Text("Se eu faço no dia de outra pessoa, ganho +1 e ela perde 1.", style = MaterialTheme.typography.bodySmall)
        } }
    }
    swap?.let { SwapDialog(vm, it) { swap = null } }
}

@Composable
private fun CalendarScreen(vm: MainVm) {
    val occ by vm.occ.collectAsState()
    val grouped = occ.groupBy { it.date }
    var swap by remember { mutableStateOf<OccE?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Calendário", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        grouped.forEach { (d, l) -> item(key = d) { Section(Fmt.day(d)) { l.forEach { OccRow(vm, it) { o -> swap = o } } } } }
        item { Text("Mostrando 14 dias atrás até 4 semanas à frente (dados salvos neste aparelho).", style = MaterialTheme.typography.bodySmall) }
    }
    swap?.let { SwapDialog(vm, it) { swap = null } }
}

@Composable
private fun TasksScreen(vm: MainVm) {
    val tasks by vm.tasks.collectAsState(); val occ by vm.occ.collectAsState()
    val today = LocalDate.now().toString()
    var swap by remember { mutableStateOf<OccE?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Tarefas", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        if (tasks.isEmpty()) item { Text("Nenhuma tarefa ainda. O administrador cria as tarefas pelo site.") }
        tasks.forEach { t ->
            if (t.mode == "event") item(key = t.id) { EventCard(vm, t) }
            else item(key = t.id) {
                Section(vm.taskName(t.id)) {
                    Text("Participam: " + vm.ids(t.participants).joinToString { vm.name(it) }.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                    if (vm.ids(t.confirmers).isNotEmpty()) Text("Confirma: " + vm.ids(t.confirmers).joinToString { vm.name(it) }, style = MaterialTheme.typography.bodySmall)
                    val l = occ.filter { it.taskId == t.id && it.status != "skipped" && (it.date >= today || it.status == "pending" || it.status == "awaiting") }.take(8)
                    if (l.isEmpty()) Text("Sem dias programados.") else l.forEach { Text(Fmt.day(it.date), style = MaterialTheme.typography.labelMedium); OccRow(vm, it) { o -> swap = o } }
                }
            }
        }
    }
    swap?.let { SwapDialog(vm, it) { swap = null } }
}

@Composable
private fun EventCard(vm: MainVm, t: TaskE) {
    val apps by vm.appliances.collectAsState(); val items by vm.checklist.collectAsState(); val ops by vm.ops.collectAsState(); val bal by vm.balances.collectAsState()
    val me = vm.me.collectAsState().value.meId
    var swap by remember { mutableStateOf(false) }
    val a = apps.firstOrNull { it.taskId == t.id }
    val usePending = ops.count { it.state == "pending" && it.type == "event.use" && it.targetKey == "use:${t.id}" }
    val submitPending = ops.any { it.state == "pending" && it.type == "cleaning.submit" }
    Section(vm.taskName(t.id) + (a?.partName?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")) {
        if (a == null) { Text("Sem dados ainda."); return@Section }
        Pill(StatusLabels[a.status] ?: a.status, if (a.status == "none" || a.status == "confirmed") GREEN else if (a.status == "awaiting") BLUE else AMBER)
        if (a.status == "awaiting") Text("Registrada, mas ainda sem a confirmação — não vale como limpeza confirmada.", style = MaterialTheme.typography.bodySmall)
        a.rejectReason?.let { Text("Rejeitada: $it", color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Vez de:"); WhoChip(vm, a.responsibleId ?: a.nextResponsibleId) }
        KeyValue("Usos desde a última limpeza", a.usageCount.toString() + if (usePending > 0) " (+$usePending pendente)" else "")
        KeyValue("Último uso", Fmt.dateTime(a.lastUsedAt))
        KeyValue("Última limpeza confirmada", Fmt.dateTime(a.lastCleanedAt))
        KeyValue("Meu saldo", (bal.firstOrNull { it.taskId == t.id && it.memberId == me }?.balance ?: 0).toString())
        Button({ vm.useEvent(t.id) }, Modifier.fillMaxWidth()) { Text("Registrar uso") }
        if (usePending > 0) Pill("$usePending uso(s) pendente(s) de envio", AMBER)
        val cyc = a.cycleId
        if (cyc != null && a.status != "none" && a.status != "confirmed") {
            val list = items.filter { it.cycleId == cyc }
            val canEdit = (a.responsibleId == me || vm.me.collectAsState().value.meRole == "admin") && a.status != "awaiting"
            Text("Checklist", style = MaterialTheme.typography.titleSmall)
            list.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.effectiveDone(c, ops), enabled = canEdit, onCheckedChange = { v -> vm.toggle(cyc, c.itemId, v) })
                    Text(c.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!canEdit && a.status != "awaiting") Text("Só quem está na vez marca as etapas.", style = MaterialTheme.typography.bodySmall)
            val allDone = list.isNotEmpty() && list.all { vm.effectiveDone(it, ops) }
            if (canEdit) Button({ vm.submit(cyc) }, Modifier.fillMaxWidth(), enabled = allDone && !submitPending) {
                Text(if (submitPending) "Envio pendente…" else if (a.needsConfirm) "Enviar para confirmação" else "Concluir limpeza")
            }
            if (a.status != "awaiting") OutlinedButton({ swap = true }, Modifier.fillMaxWidth()) { Text("Propor troca de responsável") }
        }
    }
    val openCycle = a?.cycleId
    if (swap && a != null && openCycle != null) SwapDialog(vm, OccE(taskId = t.id, key = "cycle:$openCycle", date = LocalDate.now().toString(), effectiveMemberId = a.responsibleId, plannedMemberId = a.responsibleId), isCycle = true) { swap = false }
}

@Composable
private fun SwapDialog(vm: MainVm, occ: OccE, isCycle: Boolean = false, onDismiss: () -> Unit) {
    val tasks by vm.tasks.collectAsState(); val members by vm.members.collectAsState(); val all by vm.occ.collectAsState()
    val task = tasks.firstOrNull { it.id == occ.taskId }
    val parts = vm.ids(task?.participants ?: "")
    val from = occ.effectiveMemberId ?: ""
    val targets = parts.filter { it != from && members.firstOrNull { m -> m.id == it }?.active == true }
    var kind by remember { mutableStateOf("substitution") }
    var target by remember { mutableStateOf(targets.firstOrNull()) }
    var swapOcc by remember { mutableStateOf<OccE?>(null) }
    val today = LocalDate.now().toString()
    val candidates = all.filter { it.taskId == occ.taskId && it.status == "pending" && it.date >= today && it.effectiveMemberId == target && !it.inReview && it.key != occ.key }.take(10)
    val kinds = if (isCycle) listOf("substitution") else KIND_LABEL.keys.toList()
    val ready = target != null && (kind != "swap" || swapOcc != null)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Propor troca") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text("${vm.taskName(occ.taskId)} · ${if (isCycle) "responsável atual" else Fmt.day(occ.date)}: ${vm.name(from)}", style = MaterialTheme.typography.bodySmall) }
                item { Text("Tipo", style = MaterialTheme.typography.titleSmall) }
                items(kinds) { k -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k; swapOcc = null }); Text(KIND_LABEL[k] ?: k) } }
                item { Text("Com quem", style = MaterialTheme.typography.titleSmall) }
                items(targets) { id -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(target == id, { target = id; swapOcc = null }); Text(vm.name(id)) } }
                if (kind == "swap") {
                    item { Text("Dia da outra pessoa em troca", style = MaterialTheme.typography.titleSmall) }
                    if (candidates.isEmpty()) item { Text("Nenhum dia disponível.") }
                    items(candidates) { c -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(swapOcc?.key == c.key, { swapOcc = c }); Text(Fmt.day(c.date) + if (c.slotName.isNotBlank()) " · ${c.slotName}" else "") } }
                }
                item {
                    HorizontalDivider()
                    Text("• ${KIND_LABEL[kind]}\n• ${vm.name(from)} → ${target?.let { vm.name(it) } ?: "?"}" +
                        (if (kind == "swap" && swapOcc != null) "\n• ${vm.name(target)} → ${vm.name(from)} em ${Fmt.day(swapOcc!!.date)}" else "") +
                        "\n• O saldo não muda.\n• Vale quando as pessoas envolvidas concordarem (ou o administrador aprovar).", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                val items2 = mutableListOf(Triple(occ.key, from, target!!))
                if (kind == "swap") items2.add(Triple(swapOcc!!.key, target!!, from))
                vm.propose(occ.taskId, kind, items2, "${occ.taskId}|${occ.key}"); onDismiss()
            }) { Text("Enviar pedido") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SwapsScreen(vm: MainVm) {
    val props by vm.proposals.collectAsState(); val ops by vm.ops.collectAsState(); val me = vm.me.collectAsState().value.meId
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Trocas", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        if (props.isEmpty()) item { Text("Nenhum pedido. Para pedir uma troca, use “Propor troca” em um dia seu.") }
        items(props, key = { it.id }) { p ->
            val cons = runCatching { Json.parseToJsonElement(p.consensus).jsonObject }.getOrNull()
            fun ids(k: String) = runCatching { (cons!![k] as JsonArray).map { e -> (e as JsonPrimitive).content } }.getOrDefault(emptyList())
            val approved = ids("approved"); val missing = ids("missing"); val rejected = ids("rejected")
            val voting = ops.any { it.state == "pending" && it.targetKey == "vote:${p.id}" }
            Section("${vm.taskName(p.taskId)} · ${KIND_LABEL[p.kind] ?: p.kind}") {
                Text("Pedido de ${vm.name(p.proposerId)} em ${Fmt.dateTime(p.createdAt)}", style = MaterialTheme.typography.bodySmall)
                runCatching { Json.parseToJsonElement(p.payload).jsonObject["items"] as JsonArray }.getOrNull()?.forEach { e ->
                    val o = e.jsonObject; fun s(k: String) = (o[k] as JsonPrimitive).content
                    Text("• ${s("key").take(10)}: ${vm.name(s("fromMemberId"))} → ${vm.name(s("toMemberId"))}")
                }
                Pill(when (p.status) { "waiting" -> "Aguardando respostas"; "applied" -> "Aplicada"; "rejected" -> "Rejeitada"; else -> p.status }, when (p.status) { "applied" -> GREEN; "rejected" -> RED; else -> AMBER })
                Text("Concordam: ${approved.joinToString { vm.name(it) }.ifEmpty { "—" }} · Faltam: ${missing.joinToString { vm.name(it) }.ifEmpty { "—" }}" + (if (rejected.isNotEmpty()) " · Recusaram: ${rejected.joinToString { vm.name(it) }}" else ""), style = MaterialTheme.typography.bodySmall)
                p.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (p.status == "waiting" && me != null && me in missing) {
                    if (voting) Pill("Voto pendente de envio", AMBER) else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.vote(p.id, "approve") }) { Text("Concordar") }
                        OutlinedButton({ vm.vote(p.id, "reject") }) { Text("Recusar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BalancesScreen(vm: MainVm, back: () -> Unit) {
    val led by vm.ledger.collectAsState(); val bal by vm.balances.collectAsState(); val me = vm.me.collectAsState().value.meId
    val mine = bal.filter { it.memberId == me }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(back) { Text("‹ Voltar") }; Text("Saldos", style = MaterialTheme.typography.headlineSmall) }
        item { Section("Meu saldo por tarefa") {
            if (mine.isEmpty()) Text("Sem saldos.") else mine.forEach { KeyValue(vm.taskName(it.taskId), (if (it.balance > 0) "+" else "") + it.balance) }
            KeyValue("Total", (mine.sumOf { it.balance }.let { (if (it > 0) "+" else "") + it }))
            Text("Fiz no dia de outra pessoa: +1. Fizeram no meu dia: −1.", style = MaterialTheme.typography.bodySmall)
        } }
        items(led.filter { it.memberId == me }, key = { it.id }) { l ->
            Section(vm.taskName(l.taskId)) {
                Text((if (l.delta > 0) "+" else "") + l.delta + (if (l.kind == "cover") " · dia ${l.originKey.take(10)}" else " · ${l.reason ?: "ajuste do administrador"}"))
                Pill(if (l.status == "active") "Valendo" else "Anulado", if (l.status == "active") (if (l.delta > 0) GREEN else RED) else GRAY)
            }
        }
    }
}

@Composable
private fun HistoryScreen(vm: MainVm, back: () -> Unit) {
    val occ by vm.occ.collectAsState(); val props by vm.proposals.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(back) { Text("‹ Voltar") }; Text("Histórico", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Conclusões recentes", style = MaterialTheme.typography.titleMedium) }
        items(occ.filter { it.status == "done" }.sortedByDescending { it.completedAt }, key = { it.taskId + it.key }) {
            Text("${Fmt.day(it.date)} · ${vm.taskName(it.taskId)}${if (it.slotName.isNotBlank()) " (${it.slotName})" else ""} — ${vm.name(it.completedBy)} em ${Fmt.dateTime(it.completedAt)}")
        }
        item { Text("Trocas", style = MaterialTheme.typography.titleMedium) }
        items(props.filter { it.status != "waiting" }, key = { "p" + it.id }) { Text("${Fmt.dateTime(it.createdAt)} · ${vm.taskName(it.taskId)} · ${KIND_LABEL[it.kind] ?: it.kind} · ${it.status}") }
    }
}

private val REMINDERS = mapOf("daily" to 19 * 60)

@Composable
internal fun SettingsScreen(vm: MainVm, back: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val last by vm.lastSync.collectAsState(); val upd by vm.serverUpdated.collectAsState(); val ver by vm.serverVersion.collectAsState()
    val ops by vm.ops.collectAsState(); val busy by vm.syncing.collectAsState(); val st by vm.me.collectAsState()
    val on = remember { mutableStateMapOf<String, Boolean>().apply { NOTIF_CATEGORIES.forEach { put(it.first, Notify.prefEnabled(ctx, it.first)) } } }
    val times = remember { mutableStateMapOf<String, Int>().apply { REMINDERS.forEach { (k, d) -> put(k, Notify.reminderMinutes(ctx, k, d)) } } }
    var confirmOut by remember { mutableStateOf(false) }
    val appVersion = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val cats = if (st.meRole == "subadmin") NOTIF_CATEGORIES.filter { it.first in listOf("awaiting", "sync_fail") } else NOTIF_CATEGORIES.filter { it.first != "awaiting" }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { if (back != null) TextButton(back) { Text("‹ Voltar") }; Text("Ajustes", style = MaterialTheme.typography.headlineSmall) }
        item { Section("Conta") {
            KeyValue("Perfil", st.meName ?: "—")
            KeyValue("Nível", when (st.meRole) { "admin" -> "Administrador"; "subadmin" -> "Subadmin"; else -> "Usuário" })
            KeyValue("Site", st.siteUrl ?: "—")
        } }
        item { Section("Sincronização") {
            KeyValue("Última atualização do servidor", Fmt.dateTime(upd))
            KeyValue("Última sincronização deste aparelho", Fmt.dateTime(last))
            KeyValue("Versão dos dados locais", (ver ?: "—") + if (ops.any { it.state == "pending" }) " + alterações locais" else "")
            KeyValue("Versão do aplicativo", appVersion)
            Button({ vm.sync() }, enabled = !busy) { Text(if (busy) "Atualizando…" else "Atualizar dados agora") }
        } }
        if (ops.isNotEmpty()) item { Section("Fila de alterações") {
            ops.forEach { o ->
                Text("${o.type} · ${when (o.state) { "pending" -> "pendente de envio"; "conflict" -> "conflito"; else -> "recusada" }}${o.lastError?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                if (o.state != "pending") TextButton({ vm.dismiss(o.opId) }) { Text("Descartar") }
            }
        } }
        item { Section("Notificações") {
            cats.forEach { (k, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(on[k] ?: true, { v -> on[k] = v; Notify.setEnabled(ctx, k, v) })
                }
                if (k in times) Row(verticalAlignment = Alignment.CenterVertically) {
                    val m = times[k]!!
                    Text("Horário: %02d:%02d".format(m / 60, m % 60), style = MaterialTheme.typography.bodySmall)
                    TextButton({ val n = (m - 30 + 1440) % 1440; times[k] = n; Notify.setReminderMinutes(ctx, k, n) }) { Text("−30min") }
                    TextButton({ val n = (m + 30) % 1440; times[k] = n; Notify.setReminderMinutes(ctx, k, n) }) { Text("+30min") }
                }
            }
            Text("Os lembretes são verificados junto da sincronização em segundo plano (aprox. a cada 15 min).", style = MaterialTheme.typography.bodySmall)
        } }
        item { OutlinedButton({ confirmOut = true }, Modifier.fillMaxWidth()) { Text("Sair da conta") } }
    }
    if (confirmOut) AlertDialog(onDismissRequest = { confirmOut = false }, title = { Text("Sair da conta?") },
        text = { val n = ops.count { it.state == "pending" }; Text("Os dados locais serão apagados deste aparelho." + if (n > 0) " Há $n alteração(ões) ainda não enviadas que serão PERDIDAS." else "") },
        confirmButton = { TextButton({ confirmOut = false; vm.logout() }) { Text("Sair") } }, dismissButton = { TextButton({ confirmOut = false }) { Text("Cancelar") } })
}
