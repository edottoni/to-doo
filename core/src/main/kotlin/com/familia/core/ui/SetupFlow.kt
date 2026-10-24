package com.familia.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.familia.core.net.ApiError
import com.familia.core.sync.Graph
import com.familia.core.sync.SyncOutcome
import kotlinx.coroutines.launch
import java.io.IOException

private enum class Step { Welcome, Site, Login, Loading }

/**
 * Libera o app completo somente depois de: site adicionado e verificado -> login -> dados baixados e salvos no aparelho.
 * Nas aberturas seguintes (site + sessão + dados), vai direto para o conteúdo, inclusive sem internet.
 */
@Composable
fun AppGate(title: String, subtitle: String, content: @Composable () -> Unit) {
    val st by Graph.session.state.collectAsState()
    if (st.siteUrl != null && st.loggedIn && st.ready) content() else SetupFlow(title, subtitle)
}

@Composable
private fun SetupFlow(title: String, subtitle: String) {
    val st by Graph.session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var step by remember(st.siteUrl, st.loggedIn) {
        mutableStateOf(if (st.siteUrl == null) Step.Welcome else if (!st.loggedIn) Step.Login else Step.Loading)
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("Verificando o site…") }

    suspend fun load() {
        busy = true; error = null
        progress = "Baixando tarefas, escalas e saldos…"
        when (Graph.repo.sync()) {
            SyncOutcome.OK -> progress = "Dados salvos no aparelho"
            SyncOutcome.OFFLINE -> error = "Sem conexão com o site. Verifique a internet e tente novamente."
            SyncOutcome.AUTH -> { error = "Sessão inválida. Entre novamente."; step = Step.Login }
            SyncOutcome.ERROR -> error = Graph.db.dao().kv("lastError").takeUnless { it.isNullOrBlank() } ?: "O site respondeu com erro."
        }
        busy = false
    }
    LaunchedEffect(step) { if (step == Step.Loading) load() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.systemBarsPadding().padding(24.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (step) {
                    Step.Welcome -> {
                        Text("Para começar, adicione o endereço do site da sua família. Os dados ficam salvos neste aparelho e funcionam sem internet.")
                        Button(onClick = { step = Step.Site }, Modifier.fillMaxWidth()) { Text("Adicionar site") }
                    }
                    Step.Site -> {
                        OutlinedTextField(url, { url = it }, label = { Text("Endereço do site") }, placeholder = { Text("meusite.com.br/casa") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                busy = true; error = null
                                val base = Graph.api.normalizeUrl(url)
                                if (base == null) error = "Endereço inválido."
                                else try {
                                    val p = Graph.api.ping(base)
                                    when {
                                        p.app != "familia-tarefas" -> error = "Este endereço não é um site do Casa em dia."
                                        p.setupRequired -> error = p.message.ifBlank { "O site ainda não foi instalado. Abra o endereço no navegador e conclua a instalação." }
                                        !p.db -> error = "O site está no ar, mas sem acesso ao banco de dados."
                                        else -> { Graph.session.setSite(base); step = Step.Login }
                                    }
                                } catch (e: IOException) { error = "Não foi possível conectar. Verifique o endereço e a internet." }
                                catch (e: ApiError) { error = if (e.code == "bad_body") e.message else "O site respondeu com erro (${e.status})." }
                                catch (e: Exception) { error = "Resposta inesperada do site." }
                                busy = false
                            }
                        }) { Text(if (busy) "Verificando…" else "Verificar site") }
                        TextButton(onClick = { step = Step.Welcome; error = null }) { Text("Voltar") }
                    }
                    Step.Login -> {
                        Text("Site: ${st.siteUrl}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(email, { email = it }, label = { Text("Usuário (nome do perfil)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Senha") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                            scope.launch {
                                busy = true; error = null
                                try {
                                    val r = Graph.api.login(email, password)
                                    if (Graph.session.lastMeId != null && Graph.session.lastMeId != r.me.id) Graph.db.dao().wipe() // outra conta
                                    Graph.session.saveLogin(r); password = ""
                                    step = Step.Loading
                                } catch (e: ApiError) { error = e.message }
                                catch (e: IOException) { error = "Sem conexão com o site." }
                                busy = false
                            }
                        }) { Text(if (busy) "Entrando…" else "Entrar") }
                        TextButton(onClick = { scope.launch { Graph.repo.forgetSite(); step = Step.Welcome; error = null } }) { Text("Trocar de site") }
                    }
                    Step.Loading -> {
                        if (busy) { CircularProgressIndicator(); Text(progress) }
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { scope.launch { load() } }, Modifier.fillMaxWidth()) { Text("Tentar novamente") }
                            TextButton(onClick = { scope.launch { Graph.repo.forgetSite(); step = Step.Welcome; error = null } }) { Text("Trocar de site") }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
