package com.handykit.app.core.crypto

import javax.crypto.SecretKey

/**
 * 笔记有效解锁会话。DEK 只在内存中，锁定即清空引用。
 * 所有数据访问层都要先检查 isValid；锁定后的迟到结果不得显示（K6）。
 */
class VaultSession private constructor(private var dek: SecretKey?) {
    @Volatile
    private var generation: Int = 0

    val isValid: Boolean get() = dek != null

    /** 拿 DEK 前必须校验 generation，防止锁定后拿旧引用。 */
    fun requireDek(expectedGeneration: Int): SecretKey {
        check(dek != null) { "笔记已锁定" }
        check(generation == expectedGeneration) { "会话已变更" }
        return dek!!
    }

    fun currentGeneration(): Int = generation

    fun lock() {
        dek = null
        generation++
    }

    companion object {
        fun unlocked(key: SecretKey): VaultSession = VaultSession(key)
        fun locked(): VaultSession = VaultSession(null)
    }
}
