package com.familia.core.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.familia.core.data.LoginResp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class SessionState(
    val siteUrl: String? = null, val loggedIn: Boolean = false, val ready: Boolean = false,
    val meId: String? = null, val meName: String? = null, val meRole: String? = null,
)

/** Sessão e endereço do site, guardados criptografados (Android Keystore). Senhas nunca são salvas. */
class SessionStore(private val ctx: Context) {
    private val prefs: SharedPreferences = open()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SessionState> = _state

    private fun open(): SharedPreferences {
        fun make() = EncryptedSharedPreferences.create(
            ctx, "session_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try { make() } catch (e: Exception) { ctx.deleteSharedPreferences("session_secure"); make() }
    }

    private fun read() = SessionState(
        siteUrl = prefs.getString("site", null),
        loggedIn = prefs.getString("refresh", null) != null,
        ready = prefs.getBoolean("ready", false),
        meId = prefs.getString("meId", null), meName = prefs.getString("meName", null), meRole = prefs.getString("meRole", null),
    )
    private fun publish() { _state.value = read() }

    val siteUrl: String? get() = prefs.getString("site", null)
    val accessToken: String? get() = prefs.getString("access", null)
    val refreshToken: String? get() = prefs.getString("refresh", null)
    val lastMeId: String? get() = prefs.getString("meId", null)
    val deviceId: String
        get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }

    fun setSite(url: String) { prefs.edit().putString("site", url).apply(); publish() }
    fun saveLogin(r: LoginResp) {
        prefs.edit().putString("access", r.accessToken).putString("refresh", r.refreshToken)
            .putString("meId", r.me.id).putString("meName", r.me.displayName).putString("meRole", r.me.role).apply()
        publish()
    }
    fun saveTokens(access: String, refresh: String) { prefs.edit().putString("access", access).putString("refresh", refresh).apply(); publish() }
    fun markReady() { prefs.edit().putBoolean("ready", true).apply(); publish() }
    /** Sessão inválida/revogada: remove só os tokens; os dados locais são mantidos até um novo login. */
    fun clearTokens() { prefs.edit().remove("access").remove("refresh").apply(); publish() }
    /** Sair da conta (o chamador também apaga o banco local). Mantém o endereço do site. */
    fun logout() { prefs.edit().remove("access").remove("refresh").remove("meId").remove("meName").remove("meRole").putBoolean("ready", false).apply(); publish() }
    fun forgetSite() { logout(); prefs.edit().remove("site").apply(); publish() }
}
