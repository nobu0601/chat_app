package io.github.nobu0601.icocaautocharge.balance

import android.nfc.Tag
import android.nfc.tech.NfcF
import io.github.nobu0601.icocaautocharge.core.SecureLog

/**
 * **物理の** ICOCA カードから残高を読む。
 *
 * ### これは何をしているか
 * FeliCa の「Read Without Encryption」コマンドで、
 * 交通系ICカードが **無認証で公開している** 利用履歴サービス（サービスコード 0x090F）を読む。
 * 暗号の解読も、セキュア領域への不正アクセスも行っていない。
 * カード側が「誰でも読んでよい」と定めている領域を、公開仕様どおりに読むだけである
 * （指示書 §2 の禁止事項に該当しない）。
 *
 * ### これで「できないこと」
 * **自端末のモバイルICOCAは読めない。**
 * NFC のリーダーモードは外部のカードを対象とするもので、
 * 自端末内蔵のセキュアエレメントはリーダーモードの対象にならない
 * （PROJECT_RESEARCH §2.2）。したがってこの機能は
 * 「物理カードも併用している場合の補助」でしかない。
 *
 * ### 未検証
 * 本コードは公開仕様に基づく実装だが、**実機で未検証**。
 * `docs/TECHNICAL_FEASIBILITY.md §3.5` の手順で確認すること。
 */
object FelicaBalanceReader {

    /** 交通系ICカードの利用履歴サービス（Read Without Encryption で読める公開領域）。 */
    private const val SERVICE_CODE_HISTORY = 0x090F

    /** Read Without Encryption のコマンドコード。 */
    private const val CMD_READ_WITHOUT_ENCRYPTION = 0x06.toByte()

    private const val BLOCK_SIZE = 16

    sealed interface Result {
        data class Success(val balanceYen: Int) : Result
        data class Failure(val message: String) : Result
    }

    fun read(tag: Tag): Result {
        val nfcF = NfcF.get(tag) ?: return Result.Failure("このカードはFeliCa（NfcF）ではありません")
        val idm = tag.id
        if (idm == null || idm.size != 8) return Result.Failure("IDmを取得できませんでした")

        return try {
            nfcF.connect()
            nfcF.timeout = NFC_TIMEOUT_MILLIS
            val response = nfcF.transceive(buildReadCommand(idm, blockNumber = 0))
            parseBalance(response)
        } catch (e: Exception) {
            SecureLog.w(SecureLog.Tag.BALANCE, "FeliCa read failed: ${e.javaClass.simpleName}")
            Result.Failure("カードの読み取りに失敗しました（もう一度かざしてください）")
        } finally {
            runCatching { nfcF.close() }
        }
    }

    /**
     * Read Without Encryption のコマンドパケットを組み立てる。
     *
     * 長さ(1) / コマンド(1) / IDm(8) / サービス数(1) / サービスコード(2, LE) /
     * ブロック数(1) / ブロックリスト(2)
     */
    internal fun buildReadCommand(idm: ByteArray, blockNumber: Int): ByteArray {
        val body = byteArrayOf(
            CMD_READ_WITHOUT_ENCRYPTION,
            *idm,
            0x01, // サービス数
            (SERVICE_CODE_HISTORY and 0xFF).toByte(), // サービスコード 下位バイト
            ((SERVICE_CODE_HISTORY shr 8) and 0xFF).toByte(), // 上位バイト
            0x01, // ブロック数
            0x80.toByte(), // ブロックリスト要素（2バイト長・アクセスモード0）
            blockNumber.toByte(),
        )
        return byteArrayOf((body.size + 1).toByte(), *body)
    }

    /**
     * 応答から残高を取り出す。
     *
     * 応答: 長さ(1) / レスポンスコード(1) / IDm(8) / ステータス1(1) / ステータス2(1) /
     *       ブロック数(1) / ブロックデータ(16 * n)
     *
     * ブロック内の残高は **オフセット 10 から 2 バイト・リトルエンディアン**。
     */
    internal fun parseBalance(response: ByteArray?): Result {
        if (response == null || response.size < 13) {
            return Result.Failure("応答が短すぎます")
        }
        val status1 = response[10].toInt() and 0xFF
        val status2 = response[11].toInt() and 0xFF
        if (status1 != 0x00) {
            return Result.Failure("カードがエラーを返しました (status=$status1/$status2)")
        }
        val blockCount = response[12].toInt() and 0xFF
        if (blockCount < 1) return Result.Failure("履歴ブロックがありません")
        val dataStart = 13
        if (response.size < dataStart + BLOCK_SIZE) return Result.Failure("応答が短すぎます")

        val block = response.copyOfRange(dataStart, dataStart + BLOCK_SIZE)
        val balance = (block[10].toInt() and 0xFF) or ((block[11].toInt() and 0xFF) shl 8)
        return if (balance in 0..MAX_PLAUSIBLE_BALANCE) {
            Result.Success(balance)
        } else {
            Result.Failure("読み取った値が残高としてありえません")
        }
    }

    private const val NFC_TIMEOUT_MILLIS = 2_000
    private const val MAX_PLAUSIBLE_BALANCE = 20_000
}
