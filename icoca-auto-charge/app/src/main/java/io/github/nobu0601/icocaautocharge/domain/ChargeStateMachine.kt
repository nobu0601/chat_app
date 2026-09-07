package io.github.nobu0601.icocaautocharge.domain

/**
 * 状態遷移の唯一の出入口（ARCHITECTURE §3）。
 *
 * 状態を書き換える処理をこの1クラスに閉じ込めることで、
 * 二重チャージにつながる「どこかで勝手に state を進めた」という事故を防ぐ。
 *
 * Android に依存しない純粋クラス。
 */
class ChargeStateMachine {

    sealed interface Result {
        data class Accepted(val attempt: ChargeAttempt) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * 低残高を検知して新しい試行を始める。
     *
     * @param current 進行中の試行（無ければ null）
     * @return 進行中の試行があるか、同じ残高を見ているだけなら [Result.Rejected]
     */
    fun detect(
        current: ChargeAttempt?,
        balanceYen: Int?,
        threshold: Int,
        chargeAmount: Int,
        nowMillis: Long,
    ): Result {
        if (current != null && current.state.isActive) {
            // 二重チャージ防止その1: 非終端の試行が存在する間は新規に作らない
            return Result.Rejected("attempt already in progress: ${current.state}")
        }
        if (current != null && balanceYen != null &&
            current.balanceAtDetection == balanceYen && !current.state.isTerminal
        ) {
            // 二重チャージ防止その2: 同じ残高を再観測しただけ
            return Result.Rejected("same balance re-observed: $balanceYen")
        }
        return Result.Accepted(
            ChargeAttempt(
                historyId = 0L,
                state = ChargeState.CHARGE_DETECTED,
                startedAt = nowMillis,
                updatedAt = nowMillis,
                balanceAtDetection = balanceYen,
                threshold = threshold,
                chargeAmount = chargeAmount,
            ),
        )
    }

    /**
     * 状態を1つ進める。許可されていない遷移は [Result.Rejected] を返し、
     * **例外を投げずに現状維持**する（自動操作中に落とすと復旧できないため）。
     */
    fun transition(
        attempt: ChargeAttempt,
        to: ChargeState,
        nowMillis: Long,
        automationMethod: AutomationMethod = attempt.automationMethod,
        userConfirmed: Boolean = attempt.userConfirmed,
        errorReason: ErrorReason? = attempt.errorReason,
    ): Result {
        if (to !in allowedNext(attempt.state)) {
            return Result.Rejected("illegal transition ${attempt.state} -> $to")
        }
        if (to == ChargeState.FAILED && errorReason == null) {
            return Result.Rejected("FAILED requires an errorReason")
        }
        return Result.Accepted(
            attempt.copy(
                state = to,
                updatedAt = nowMillis,
                automationMethod = automationMethod,
                userConfirmed = userConfirmed,
                errorReason = errorReason,
            ),
        )
    }

    /**
     * 放置された試行を強制的に終端へ落とす。
     *
     * これが無いと、ユーザーがチャージ途中で離脱した場合に state が
     * CHARGE_PENDING のまま残り、以後まったく発火しなくなる。
     */
    fun timeoutIfStale(attempt: ChargeAttempt, nowMillis: Long): Result? {
        if (!attempt.state.isActive) return null
        if (nowMillis - attempt.updatedAt < ATTEMPT_TIMEOUT_MILLIS) return null
        return Result.Accepted(
            attempt.copy(
                state = ChargeState.FAILED,
                updatedAt = nowMillis,
                errorReason = ErrorReason.TIMEOUT,
            ),
        )
    }

    private fun allowedNext(from: ChargeState): Set<ChargeState> = when (from) {
        ChargeState.IDLE -> setOf(ChargeState.CHARGE_DETECTED)
        ChargeState.CHARGE_DETECTED -> setOf(
            ChargeState.CHARGE_PENDING,
            ChargeState.FAILED,
        )
        ChargeState.CHARGE_PENDING -> setOf(
            ChargeState.CHARGING,
            ChargeState.FAILED,
        )
        ChargeState.CHARGING -> setOf(
            ChargeState.VERIFYING,
            ChargeState.FAILED,
        )
        ChargeState.VERIFYING -> setOf(
            ChargeState.SUCCESS,
            ChargeState.FAILED,
        )
        // 終端からは IDLE に戻すことだけ許す（クールダウン後の再開）
        ChargeState.SUCCESS, ChargeState.FAILED -> setOf(ChargeState.IDLE)
    }

    companion object {
        /** 進行中の試行を放棄するまでの時間。 */
        const val ATTEMPT_TIMEOUT_MILLIS = 30L * 60 * 1000
    }
}
