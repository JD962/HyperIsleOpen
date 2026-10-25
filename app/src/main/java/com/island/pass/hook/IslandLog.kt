package com.island.pass.hook

import com.hchen.hooktool.ModuleConfig
import com.hchen.hooktool.log.XposedLog

/**
 * 模块日志（单一通道：Xposed 框架日志）。
 *
 *  - 统一经 HookTool [XposedLog]（TAG=IslandPass），LSPosed 管理器「日志」页
 *    可直接查看（可在页内搜索过滤）；
 *  - 1.0.18 起移除 logcat 双通道：hook 侧（宿主进程内）XposedLog 直写框架日志；
 *    模块 App 自身进程无框架日志写入通道（XposedService API 102 无 log 方法），
 *    由 XposedLog 内置回落至 logcat（属 HookTool 自身行为，非本模块的通道）；
 *
 * **日志级别策略（1.0.20 起，用户明确要求）**：
 *  - [d] 详细日志（受 verbose_log 偏好控制，默认关；开启时联动 HookTool
 *    日志级别至 LOG_D 放行 DEBUG 级，关闭时回落 LOG_I）；
 *  - [w]/[e] 始终输出，且**仅限五类语义**（verbose 关闭时的常驻可见面）：
 *    1. 注入成功（hook 链安装 / 门禁就绪等关键成功节点）；
 *    2. 状态改变（功能开关 / 名单模式的实际切换）；
 *    3. 注入失败（hook 安装失败、目标类未找到）；
 *    4. 功能失效（保存失败、镜像提交返回 false、设备不支持等）；
 *    5. 通讯错误（service 死亡、通道注册失败、广播/回报失败）；
 *  - 其余一切过程明细（诊断判定、配置快照、loader 扫描、类名枚举等）
 *    一律 [d] 级，即默认关闭、仅 verbose 开启时输出——避免常驻日志过详细。
 */
internal object IslandLog {

    private const val TAG = "IslandPass"

    /** 详细日志开关（Hooker onHook 时由偏好读取一次；联动 HookTool 日志级别） */
    @Volatile
    var verbose: Boolean = false
        set(value) {
            field = value
            runCatching {
                ModuleConfig.setLogLevel(if (value) ModuleConfig.LOG_D else ModuleConfig.LOG_I)
            }
        }

    fun d(msg: String) {
        if (!verbose) return
        XposedLog.logD(TAG, msg)
    }

    fun w(msg: String) {
        XposedLog.logW(TAG, msg)
    }

    fun e(msg: String) {
        XposedLog.logE(TAG, msg)
    }
}
