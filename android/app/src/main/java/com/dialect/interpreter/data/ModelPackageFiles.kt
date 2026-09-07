package com.dialect.interpreter.data

import java.io.File
import java.io.IOException

/** Directory replacement and recovery, called while ModelAccessGate is exclusive. */
internal class ModelPackageFiles(
    private val root: File,
    private val move: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) {
    fun activate(packageId: String) {
        checkPackageId(packageId)
        val staging = File(root, ".staging/$packageId")
        val active = File(root, packageId)
        val backup = File(root, ".backup/$packageId")
        if (!staging.isDirectory) throw IOException("模型暂存目录不存在")
        if (!backup.parentFile!!.isDirectory && !backup.parentFile!!.mkdirs()) {
            throw IOException("无法创建模型备份目录")
        }
        if (backup.exists()) throw IOException("存在待恢复的模型备份，请先刷新模型状态")
        val hadPrevious = active.exists()
        if (hadPrevious && !move(active, backup)) throw IOException("无法备份当前模型版本")
        if (!move(staging, active)) {
            if (hadPrevious && !move(backup, active)) {
                throw IOException("新版本启用失败，旧版本保留在备份目录，刷新后可恢复")
            }
            throw IOException("无法启用新模型版本，旧版本已保留")
        }
        // Once activation succeeds, a leftover backup can also be cleaned on
        // the next idle refresh; cleanup failure must not remove active files.
        backup.deleteRecursively()
    }

    fun recover(packageId: String) {
        checkPackageId(packageId)
        val active = File(root, packageId)
        val backup = File(root, ".backup/$packageId")
        if (backup.exists() && !active.exists() && !move(backup, active)) {
            throw IOException("无法恢复模型 $packageId 的旧版本；备份已保留")
        }
        if (active.exists()) backup.deleteRecursively()
        File(root, ".staging/$packageId").deleteRecursively()
    }

    private fun checkPackageId(packageId: String) {
        require(packageId in setOf("asr", "mt", "tts")) { "Unknown model package: $packageId" }
    }
}
