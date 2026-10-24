package com.familia.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Entidades Room que também são o formato JSON do servidor (/api/v1/sync/pull).

@Entity(tableName = "members") @Serializable
data class MemberE(@PrimaryKey val id: String, val displayName: String, val role: String, val color: String = "#0E7490", val active: Boolean = true)

@Entity(tableName = "tasks") @Serializable
data class TaskE(
    @PrimaryKey val id: String, val mode: String, val name: String, val emoji: String = "", val color: String = "#0E7490",
    val config: String = "{}", val participants: String = "", val confirmers: String = "",
)

@Entity(tableName = "occurrences", primaryKeys = ["taskId", "k"]) @Serializable
data class OccE(
    val taskId: String,
    @ColumnInfo(name = "k") val key: String,
    val date: String, val slot: Int = 0, val slotName: String = "",
    val plannedMemberId: String? = null, val effectiveMemberId: String? = null,
    val status: String = "pending", val completedBy: String? = null, val completedAt: String? = null,
    val confirmedBy: String? = null, val source: String? = null, val rejectReason: String? = null, val note: String? = null,
    val version: Int = 0, val inReview: Boolean = false, val needsConfirm: Boolean = false,
)

@Entity(tableName = "appliance") @Serializable
data class ApplianceE(
    @PrimaryKey val taskId: String, val partName: String = "", val cycleId: String? = null, val status: String = "none",
    val responsibleId: String? = null, val nextResponsibleId: String? = null, val usageCount: Int = 0,
    val lastUsedAt: String? = null, val lastUsedBy: String? = null, val submittedAt: String? = null,
    val performedBy: String? = null, val rejectReason: String? = null, val version: Int = 0,
    val lastCleanedAt: String? = null, val lastCleanedBy: String? = null, val lastConfirmedBy: String? = null, val needsConfirm: Boolean = false,
)

@Entity(tableName = "checklist", primaryKeys = ["cycleId", "itemId"]) @Serializable
data class ChecklistE(
    val cycleId: String, val itemId: String, val position: Int = 0, val description: String,
    val done: Boolean = false, val doneBy: String? = null, val doneAt: String? = null,
)

@Entity(tableName = "ledger") @Serializable
data class LedgerE(
    @PrimaryKey val id: String, val groupId: String = "", val taskId: String, val memberId: String, val delta: Int,
    val originKey: String = "", val kind: String = "cover", val status: String = "active", val reason: String? = null, val createdAt: String = "",
)

@Entity(tableName = "balances", primaryKeys = ["taskId", "memberId"]) @Serializable
data class BalanceE(val taskId: String, val memberId: String, val balance: Int)

@Entity(tableName = "proposals") @Serializable
data class ProposalE(
    @PrimaryKey val id: String, val taskId: String, val proposerId: String, val kind: String, val payload: String,
    val status: String, val needsAdmin: Boolean = false, val reason: String? = null, val note: String? = null,
    val createdAt: String = "", val votes: String = "[]", val consensus: String = "{}",
)

@Entity(tableName = "confirmations") @Serializable
data class ConfirmationE(
    @PrimaryKey val id: String, val taskId: String = "", val kind: String = "", val ref: String = "", val decision: String,
    val reason: String? = null, val createdAt: String = "", val cleanedBy: String? = null,
)

/** Fila persistente de operações offline. state: pending | conflict | rejected. */
@Entity(tableName = "ops", indices = [Index(value = ["opId"], unique = true)])
data class OpE(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String, val type: String, val payload: String, val baseVersion: Int? = null,
    val clientTime: String, val targetKey: String, val state: String = "pending", val lastError: String? = null,
)

@Entity(tableName = "kv")
data class KvE(@PrimaryKey @ColumnInfo(name = "k") val key: String, val value: String)

@Serializable data class MeDto(val id: String, val displayName: String, val role: String)
@Serializable data class FamilyDto(val name: String = "", val timezone: String = "")

@Serializable
data class Snapshot(
    val unchanged: Boolean = false, val apiVersion: Int = 1, val serverVersion: Long = 0, val serverUpdatedAt: String? = null,
    val today: String = "", val me: MeDto? = null, val family: FamilyDto? = null,
    val members: List<MemberE> = emptyList(), val tasks: List<TaskE> = emptyList(), val occurrences: List<OccE> = emptyList(),
    val appliances: List<ApplianceE> = emptyList(), val checklist: List<ChecklistE> = emptyList(),
    val ledger: List<LedgerE> = emptyList(), val balances: List<BalanceE> = emptyList(),
    val proposals: List<ProposalE> = emptyList(), val confirmations: List<ConfirmationE> = emptyList(),
)

@Serializable data class OpDto(val opId: String, val type: String, val payload: kotlinx.serialization.json.JsonElement, val baseVersion: Int? = null, val clientTime: String)
@Serializable data class PushReq(val ops: List<OpDto>)
@Serializable data class OpResultDto(val opId: String, val status: String, val code: String? = null, val message: String? = null)
@Serializable data class PushResp(val results: List<OpResultDto> = emptyList(), val serverVersion: Long = 0, val serverUpdatedAt: String? = null)
@Serializable data class LoginResp(val accessToken: String, val refreshToken: String, val expiresAt: Long = 0, val me: MeDto)
@Serializable data class RefreshResp(val accessToken: String, val refreshToken: String, val expiresAt: Long = 0)
@Serializable data class Ping(val app: String = "", val apiVersion: Int = 0, val serverTime: String = "", val db: Boolean = false, val setupRequired: Boolean = false, val needsInstall: Boolean = false, val state: String = "", val message: String = "")
