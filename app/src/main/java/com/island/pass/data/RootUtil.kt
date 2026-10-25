package com.island.pass.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root 权限工具（重启作用域应用专用）。
 *
 * 模块整体不依赖 Root：Hook 注入由 LSPosed 完成，配置经偏好桥共享；
 * 仅「重启作用域应用」需要对系统进程执行 kill，故单独探测 su 授权。
 *
 * 检测策略：枚举常见 su 路径 -> 执行 `su -c id` -> 输出含 `uid=0` 视为已授权。
 * 兼容 Magisk / KernelSU / SuperSU 等一切提供标准 su 二进制的实现。
 */
object RootUtil {

    /** Root 授权状态 */
    enum class State { CHECKING, GRANTED, DENIED }

    /** 允许重启的宿主包名白名单（防止 shell 命令拼接注入） */
    private val ALLOWED_PKGS = setOf(
        IslandConfig.PKG_SYSTEMUI,
        IslandConfig.PKG_NOTICEFLOW
    )

    /** 常见 su 路径（含 Magisk 调试 ramdisk 与 KSU） */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/su"
    )

    /** 找到可用的 su 二进制路径；未 Root 返回 null */
    fun findSu(): String? = SU_PATHS.firstOrNull { path ->
        runCatching {
            val f = File(path)
            f.exists() && f.canExecute()
        }.getOrDefault(false)
    }

    /**
     * 检测 Root 授权（IO 线程执行，含 su 授权弹窗等待）。
     *
     * @return true 表示 su 可用且已授权（uid=0）
     */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        val su = findSu() ?: return@withContext false
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * 以 Root 杀死指定应用进程。
     *
     * 策略（先 kill -9，后 am force-stop 回落）：
     *  - `kill -9`：进程直接死亡。systemui 为 persistent 系统进程，
     *    AMS（ActivityManagerService）检测到 persistent 应用进程死亡后会
     *    **立即自动重启**——这是 Android 的系统机制，故 kill 后无需、
     *    也无法（systemui 无 Launcher 入口）由模块主动唤起；
     *  - `am force-stop` 回落：会把应用置入 stopped 状态（拒绝广播/Intent，
     *    直到用户手动启动），persistent 进程虽最终也会被 AMS 重启，
     *    但恢复路径更长，仅作兜底。
     *
     * noticeflow 为非常驻应用，kill 后由模块侧经 Launcher Intent 主动拉起
     * （见 SettingsActivity.restartTargets 的 warmLaunch）。
     *
     * @param pkg 包名，必须在 [ALLOWED_PKGS] 白名单内（防命令拼接注入）
     * @return true 表示进程已被杀死
     */
    fun forceStop(pkg: String): Boolean {
        if (pkg !in ALLOWED_PKGS) return false
        val su = findSu() ?: return false
        val killed = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", "kill -9 \$(pidof $pkg)"))
            process.waitFor() == 0
        }.getOrDefault(false)
        if (killed) return true
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", "am force-stop $pkg"))
            process.waitFor() == 0
        }.getOrDefault(false)
    }
}
