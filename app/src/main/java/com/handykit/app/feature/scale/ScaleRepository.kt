package com.handykit.app.feature.scale

import android.content.Context
import com.handykit.app.core.db.AppDatabase
import com.handykit.app.core.db.Measurement
import com.handykit.app.core.db.ScaleDao
import com.handykit.app.core.db.ScaleMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.math.abs

/**
 * 体脂秤数据仓库：成员 CRUD、测量归属判定、按成员参数重算、级联删除。
 * 依赖由调用方手动构造（context.applicationContext），不使用 Hilt。
 */
class ScaleRepository(context: Context) {
    private val dao: ScaleDao = AppDatabase.get(context.applicationContext).scaleDao()

    val members: Flow<List<ScaleMember>> = dao.observeMembers()
    val measurements: Flow<List<Measurement>> = dao.observeMeasurements()

    suspend fun allMembers(): List<ScaleMember> = dao.members()

    suspend fun allMeasurements(): List<Measurement> = dao.measurements()

    fun measurementsFor(memberId: String): Flow<List<Measurement>> = dao.measurementsFor(memberId)

    suspend fun upsertMember(member: ScaleMember) {
        dao.upsertMember(member)
    }

    /** 删除成员时先级联删除其全部测量记录。 */
    suspend fun deleteMemberCascade(memberId: String) {
        val member = dao.members().firstOrNull { it.id == memberId } ?: return
        dao.deleteByMember(memberId)
        dao.deleteMember(member)
    }

    /** 未绑定成员的测量也使用参考身高算 BMI，但不生成任何 BIA 结果。 */
    suspend fun saveMeasurement(raw: Measurement, memberId: String? = null): Measurement {
        val base = raw.copy(
            id = raw.id.ifBlank { UUID.randomUUID().toString() },
            measuredAtEpochMs = if (raw.measuredAtEpochMs > 0L) raw.measuredAtEpochMs else System.currentTimeMillis(),
        )
        val member = dao.members().firstOrNull { it.id == memberId }
        val final = if (member != null) {
            BodyAlgorithm.recalculateForMember(base, member)
        } else {
            val referenceHeight = dao.members().firstOrNull()?.heightCm ?: DEFAULT_HEIGHT_CM
            val bmiOnly = BodyAlgorithm.calculate(base.weightKg, 0.0, "MALE", referenceHeight, 0L)
            base.copy(
                bmi = bmiOnly.bmi,
                bodyFatPct = 0.0,
                muscleKg = 0.0,
                waterPct = 0.0,
                proteinPct = 0.0,
                boneMassKg = 0.0,
                basalMetabolic = 0.0,
                memberId = null,
                memberNameSnapshot = null,
                algorithmVersion = bmiOnly.algorithmVersion,
            )
        }
        dao.upsertMeasurement(final)
        return final
    }

    /** 在历史详情中修正归属：按新成员参数重算所有可重算项。 */
    suspend fun reassignMeasurement(measurementId: String, memberId: String?) {
        val measurement = dao.measurements().firstOrNull { it.id == measurementId } ?: return
        val member = dao.members().firstOrNull { it.id == memberId }
        val updated = if (member != null) {
            BodyAlgorithm.recalculateForMember(measurement, member)
        } else {
            measurement.copy(memberId = null, memberNameSnapshot = null)
        }
        dao.upsertMeasurement(updated)
    }

    /** 成员身高/生日/性别修改后，刷新其历史记录的可重算项。 */
    suspend fun recalculateMember(memberId: String) {
        val member = dao.members().firstOrNull { it.id == memberId } ?: return
        dao.measurementsFor(memberId).first()
            .map { BodyAlgorithm.recalculateForMember(it, member) }
            .forEach { dao.upsertMeasurement(it) }
    }

    suspend fun deleteMeasurement(measurementId: String) {
        dao.deleteMeasurement(measurementId)
    }

    /** 按体重智能匹配成员。匹配歧义或超出阈值时必须交给用户选择，绝不静默绑定。 */
    fun resolveMemberByWeight(
        weightKg: Double,
        allMembers: List<ScaleMember>,
        confirmedMemberId: String? = null,
    ): MemberResolution {
        if (!confirmedMemberId.isNullOrBlank()) {
            val confirmed = allMembers.firstOrNull { it.id == confirmedMemberId }
            if (confirmed != null) return MemberResolution.Bound(confirmed)
        }
        if (allMembers.isEmpty()) return MemberResolution.NeedChoice(emptyList())
        if (allMembers.size == 1) return MemberResolution.Bound(allMembers.first())

        val ranked = allMembers
            .map { it to abs(it.referenceWeightKg - weightKg) }
            .filter { it.second <= MATCH_THRESHOLD_KG }
            .sortedBy { it.second }
        return when (ranked.size) {
            1 -> MemberResolution.Bound(ranked.first().first)
            else -> {
                // 0 个（超阈值）或多个候选：全部列出让用户明确选择。
                val ordered = ranked.map { it.first } + allMembers
                MemberResolution.NeedChoice(ordered.distinctBy { it.id })
            }
        }
    }

    sealed interface MemberResolution {
        data class Bound(val member: ScaleMember) : MemberResolution
        data class NeedChoice(val candidates: List<ScaleMember>) : MemberResolution
    }

    companion object {
        private const val MATCH_THRESHOLD_KG = 10.0
        private const val DEFAULT_HEIGHT_CM = 170.0
    }
}
