package com.handykit.app.core.crypto

/**
 * 应用级笔记解锁会话共享点。
 *
 * 只持有会话引用供备份模块在导出笔记域时判断"已解锁"，
 * 不提供任何笔记正文读取接口——正文访问仍走 NoteRepository 且必须携带有效会话。
 * 锁定由 NoteRepository 同步清空。
 */
object VaultSessionHolder {
    @Volatile
    private var session: VaultSession? = null

    fun current(): VaultSession? = session

    fun publish(s: VaultSession) {
        session = s
    }

    fun clear() {
        session = null
    }

    val isUnlocked: Boolean get() = session?.isValid == true
}
