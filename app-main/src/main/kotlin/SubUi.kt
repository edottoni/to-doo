package com.familia.tarefas

import android.Manifest
import android.app.DatePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.familia.core.data.*
import com.familia.core.sync.SyncScheduler
import com.familia.core.ui.*
import java.time.LocalDate

/** Interface do perfil Subadmin: confirma tarefas e marca dias como feitos. Só vê as tarefas em que é confirmador. */
@Composable
fun SubRoot() {
    val vm: MainVm = viewModel()
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS); SyncScheduler.syncNow(ctx) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Confirmar" to "✔", "Dias" to "▦", "Histórico" to "☰", "Ajustes" to "⚙").forEachIndexed { i, (l, ic) -> NavigationBarItem(tab == i, { tab = i }, icon = { Text(ic) }, label = { Text(l, maxLines = 1) }) }
        }
    }) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { when (tab) { 0 -> ConfirmScreen(vm); 1 -> SubDaysScreen(vm); 2 -> SubHistoryScreen(vm); else -> SettingsScreen(vm) } } }
}

@Composable
private fun ReasonDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(reason, { reason = it }, label = { Text("Motivo (opcional)") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onConfirm(reason); onDismiss() }) { Text("Rejeitar") } }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
private fun DecisionState(vm: MainVm, ops: List<OpE>, target: String) {
    val pending = ops.any { it.state == "pending" && it.targetKey == target }
    val bad = ops.lastOrNull { it.state != "pending" && it.targetKey == target }
    if (pending) {
        Pill("Decisão pendente de envio — ainda NÃO confirmada no servidor", AMBER)
        Text("Ela está salva neste aparelho e será enviada quando houver conexão.", style = MaterialTheme.typography.bodySmall)
    }
    if (bad != null) {
        Text("O registro mudou no servidor: ${bad.lastError ?: "não foi possível aplicar sua decisão"}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button({ vm.discardAndRefresh(bad.opId) }) { Text("Descartar minha decisão e atualizar") }
    }
}

@Composable
private fun ConfirmScreen(vm: MainVm) {
    val occ by vm.occ.collectAsState(); val apps by vm.appliances.collectAsState(); val checklist by vm.checklist.collectAsState(); val ops by vm.ops.collectAsState()
    var rejectOcc by remember { mutableStateOf<OccE?>(null) }; var rejectApp by remember { mutableStateOf<ApplianceE?>(null) }
    val waiting = occ.filter { it.status == "awaiting" }; val waitingApps = apps.filter { it.status == "awaiting" }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Aguardando confirmação", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        if (waiting.isEmpty() && waitingApps.isEmpty()) item { Text("Nada aguardando a sua decisão. ✅") }
        items(waiting, key = { it.taskId + it.key }) { o ->
            val target = "confirm:${o.taskId}|${o.key}"; val queued = ops.any { it.state == "pending" && it.targetKey == target }
            Section(vm.taskName(o.taskId) + if (o.slotName.isNotBlank()) " · ${o.slotName}" else "") {
                KeyValue("Dia", Fmt.day(o.date))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Dia de:"); WhoChip(vm, o.effectiveMemberId) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Registrado por:"); WhoChip(vm, o.completedBy) }
                KeyValue("Registrado em", Fmt.dateTime(o.completedAt))
                DecisionState(vm, ops, target)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.confirmOcc(o, true, null) }, enabled = !queued) { Text("Confirmar") }
                    OutlinedButton({ rejectOcc = o }, enabled = !queued) { Text("Rejeitar…") }
                }
            }
        }
        items(waitingApps, key = { "c" + it.taskId }) { a ->
            val id = a.cycleId ?: return@items
            val target = "confirm:$id"; val queued = ops.any { it.state == "pending" && it.targetKey == target }
            Section(vm.taskName(a.taskId) + (a.partName.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Quem fez:"); WhoChip(vm, a.performedBy ?: a.responsibleId) }
                KeyValue("Enviado em", Fmt.dateTime(a.submittedAt)); KeyValue("Usos desde a última limpeza", a.usageCount.toString())
                Text("Checklist enviado", style = MaterialTheme.typography.titleSmall)
                checklist.filter { it.cycleId == id }.forEach { Text((if (it.done) "☑ " else "☐ ") + it.description) }
                DecisionState(vm, ops, target)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.confirmCycle(a, true, null) }, enabled = !queued) { Text("Confirmar limpeza") }
                    OutlinedButton({ rejectApp = a }, enabled = !queued) { Text("Rejeitar…") }
                }
            }
        }
    }
    rejectOcc?.let { o -> ReasonDialog("Rejeitar registro", { r -> vm.confirmOcc(o, false, r) }) { rejectOcc = null } }
    rejectApp?.let { a -> ReasonDialog("Rejeitar limpeza", { r -> vm.confirmCycle(a, false, r) }) { rejectApp = null } }
}

@Composable
private fun SubDaysScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val occ by vm.occ.collectAsState(); val tasks by vm.tasks.collectAsState(); val ops by vm.ops.collectAsState()
    val today = LocalDate.now().toString()
    var pick by remember { mutableStateOf<OccE?>(null) }
    val open = occ.filter { it.status == "pending" && it.date <= today }.sortedByDescending { it.date }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Dias das minhas tarefas", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        item { Text("Se alguém fez e não registrou, marque o dia como feito aqui: vale como confirmado, sem o usuário precisar registrar.", style = MaterialTheme.typography.bodySmall) }
        tasks.filter { it.mode == "event" }.forEach { t ->
            item(key = "ev" + t.id) {
                val washed = ops.any { it.state == "pending" && it.targetKey == "washed:${t.id}" }
                Section(vm.taskName(t.id)) {
                    Text("Tarefa por uso: marque um dia em que foi feita.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({
                        val now = LocalDate.now()
                        DatePickerDialog(ctx, { _, y, m, d -> vm.markWashed(t.id, LocalDate.of(y, m + 1, d).toString()) }, now.year, now.monthValue - 1, now.dayOfMonth).show()
                    }, Modifier.fillMaxWidth(), enabled = !washed) { Text(if (washed) "Registro pendente de envio" else "Marcar um dia como feito") }
                }
            }
        }
        if (open.isEmpty()) item { Text("Nenhum dia pendente.") }
        items(open, key = { "o" + it.taskId + it.key }) { o ->
            val queued = ops.any { it.state == "pending" && it.targetKey == "markdone:${o.taskId}|${o.key}" }
            Section(vm.taskName(o.taskId) + if (o.slotName.isNotBlank()) " · ${o.slotName}" else "") {
                KeyValue("Dia", Fmt.day(o.date))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Dia de:"); WhoChip(vm, o.effectiveMemberId) }
                if (queued) Pill("Marcação pendente de envio", AMBER)
                Button({ pick = o }, enabled = !queued) { Text("Marcar como feito") }
            }
        }
    }
    pick?.let { o ->
        val t = tasks.firstOrNull { it.id == o.taskId }
        val who = listOfNotNull(o.effectiveMemberId) + vm.ids(t?.participants ?: "").filter { it != o.effectiveMemberId }
        var sel by remember(o.key) { mutableStateOf<String?>(o.effectiveMemberId) }
        AlertDialog(onDismissRequest = { pick = null }, title = { Text("Quem fez?") },
            text = { Column { who.forEach { id -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(sel == id, { sel = id }); Text(vm.name(id) + if (id == o.effectiveMemberId) " (dono do dia)" else "") } } } },
            confirmButton = { TextButton({ vm.markDoneOcc(o, sel); pick = null }) { Text("Marcar feito") } }, dismissButton = { TextButton({ pick = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun SubHistoryScreen(vm: MainVm) {
    val list by vm.confirmations.collectAsState(); val ops by vm.ops.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Minhas confirmações", style = MaterialTheme.typography.headlineSmall); SyncBar(vm) }
        items(ops.filter { it.type in listOf("occurrence.confirm", "occurrence.markDone", "cleaning.confirm", "cleaning.markWashed") }, key = { it.opId }) {
            Section("Decisão local") { Pill(if (it.state == "pending") "Pendente de envio" else if (it.state == "conflict") "Conflito" else "Recusada", if (it.state == "pending") AMBER else RED) }
        }
        if (list.isEmpty()) item { Text("Nenhuma confirmação ainda.") }
        items(list, key = { it.id }) {
            Section(Fmt.dateTime(it.createdAt)) {
                Pill(when (it.decision) { "accept" -> "Confirmada"; "reject" -> "Rejeitada"; else -> "Marcada como feita" }, if (it.decision == "reject") RED else GREEN)
                KeyValue("Tarefa", vm.taskName(it.taskId))
                if (it.kind == "occurrence") KeyValue("Dia", Fmt.day(it.ref.take(10)))
                if (it.cleanedBy != null) KeyValue("Quem fez", vm.name(it.cleanedBy))
                it.reason?.takeIf { r -> r.isNotBlank() && it.decision == "reject" }?.let { r -> Text("Motivo: $r", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
