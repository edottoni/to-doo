package com.familia.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AppDao {
    @Query("select * from members order by displayName") abstract fun members(): Flow<List<MemberE>>
    @Query("select * from members") abstract suspend fun membersOnce(): List<MemberE>
    @Query("select * from tasks order by name") abstract fun tasks(): Flow<List<TaskE>>
    @Query("select * from tasks") abstract suspend fun tasksOnce(): List<TaskE>
    @Query("select * from occurrences order by date, taskId, slot") abstract fun occurrences(): Flow<List<OccE>>
    @Query("select * from occurrences where date between :a and :b order by date, taskId, slot") abstract suspend fun occurrencesBetween(a: String, b: String): List<OccE>
    @Query("select * from appliance order by partName") abstract fun appliances(): Flow<List<ApplianceE>>
    @Query("select * from appliance") abstract suspend fun appliancesOnce(): List<ApplianceE>
    @Query("select * from checklist order by position") abstract fun checklist(): Flow<List<ChecklistE>>
    @Query("select * from ledger order by createdAt desc") abstract fun ledger(): Flow<List<LedgerE>>
    @Query("select * from ledger") abstract suspend fun ledgerOnce(): List<LedgerE>
    @Query("select * from balances") abstract fun balances(): Flow<List<BalanceE>>
    @Query("select * from balances") abstract suspend fun balancesOnce(): List<BalanceE>
    @Query("select * from proposals order by createdAt desc") abstract fun proposals(): Flow<List<ProposalE>>
    @Query("select * from proposals order by createdAt desc") abstract suspend fun proposalsOnce(): List<ProposalE>
    @Query("select * from confirmations order by createdAt desc") abstract fun confirmations(): Flow<List<ConfirmationE>>

    @Query("select * from ops order by seq") abstract fun ops(): Flow<List<OpE>>
    @Query("select * from ops where state = 'pending' order by seq") abstract suspend fun pendingOps(): List<OpE>
    @Query("select count(*) from ops where state = 'pending'") abstract suspend fun pendingCount(): Int
    @Insert abstract suspend fun insertOp(op: OpE)
    @Query("delete from ops where opId = :id") abstract suspend fun deleteOp(id: String)
    @Query("update ops set state = :s, lastError = :e where opId = :id") abstract suspend fun markOp(id: String, s: String, e: String?)

    @Query("select value from kv where k = :key") abstract suspend fun kv(key: String): String?
    @Query("select value from kv where k = :key") abstract fun kvFlow(key: String): Flow<String?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putKv(e: KvE)

    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insMembers(l: List<MemberE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insTasks(l: List<TaskE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insOcc(l: List<OccE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insAppliances(l: List<ApplianceE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insChecklist(l: List<ChecklistE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insLedger(l: List<LedgerE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insBalances(l: List<BalanceE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insProposals(l: List<ProposalE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insConfirmations(l: List<ConfirmationE>)

    @Query("delete from members") abstract suspend fun clMembers()
    @Query("delete from tasks") abstract suspend fun clTasks()
    @Query("delete from occurrences") abstract suspend fun clOcc()
    @Query("delete from appliance") abstract suspend fun clAppliances()
    @Query("delete from checklist") abstract suspend fun clChecklist()
    @Query("delete from ledger") abstract suspend fun clLedger()
    @Query("delete from balances") abstract suspend fun clBalances()
    @Query("delete from proposals") abstract suspend fun clProposals()
    @Query("delete from confirmations") abstract suspend fun clConfirmations()
    @Query("delete from ops") abstract suspend fun clOps()
    @Query("delete from kv") abstract suspend fun clKv()

    /** Troca os dados oficiais de uma vez, sem tocar na fila de operações locais. */
    @Transaction
    open suspend fun replaceAll(s: Snapshot) {
        clMembers(); clTasks(); clOcc(); clAppliances(); clChecklist(); clLedger(); clBalances(); clProposals(); clConfirmations()
        insMembers(s.members); insTasks(s.tasks); insOcc(s.occurrences); insAppliances(s.appliances)
        insChecklist(s.checklist); insLedger(s.ledger); insBalances(s.balances)
        insProposals(s.proposals); insConfirmations(s.confirmations)
        putKv(KvE("serverVersion", s.serverVersion.toString()))
        s.serverUpdatedAt?.let { putKv(KvE("serverUpdatedAt", it)) }
        s.today.takeIf { it.isNotEmpty() }?.let { putKv(KvE("serverToday", it)) }
    }

    @Transaction
    open suspend fun wipe() {
        clMembers(); clTasks(); clOcc(); clAppliances(); clChecklist(); clLedger(); clBalances(); clProposals(); clConfirmations(); clOps(); clKv()
    }
}

@Database(
    entities = [MemberE::class, TaskE::class, OccE::class, ApplianceE::class, ChecklistE::class, LedgerE::class,
        BalanceE::class, ProposalE::class, ConfirmationE::class, OpE::class, KvE::class],
    version = 2, exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(ctx: Context, name: String): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, name).fallbackToDestructiveMigration().build()
    }
}
