package com.island.pass

import android.app.Application
import com.island.pass.data.ChannelKeys
import com.island.pass.data.IslandChannel
import com.island.pass.data.IslandFramework
import com.island.pass.data.IslandListManager
import com.island.pass.data.ListSyncCoordinator

/**
 * 模块 Application（libxposed 迁移版，普通 Application）。
 *
 * onCreate 中初始化模块 App 侧设施：
 *  - [IslandFramework.init]：注册 XposedServiceHelper 监听（远程偏好镜像入口 / 注入状态判定；
 *    service 绑定时主线程执行 ConfigMirror 迁移 + 镜像 + 名单校验，见 [ConfigMirror]）；
 *  - [IslandChannel.registerModuleSide]：注册宿主持 STATUS 回报的广播接收器（进程级）；
 *  - [IslandChannel.listenCommands]：接收宿主 LIST_REQUEST / LIST_VERIFY_REPLY（名单同步协议）；
 *  - [IslandListManager.init]：旧版三态共享名单一次性迁移 + 汇总生效名单写入本地偏好并镜像远程。
 *
 * hooker 侧装载入口见 [IslandModuleEntry]（META-INF/xposed/java_init.list）。
 */
class IslandPassApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        IslandFramework.init(this)
        IslandChannel.registerModuleSide(this)
        // 名单同步协议（1.0.22）：宿主重启请求 / 指纹回报
        IslandChannel.listenCommands { key, from, value ->
            when (key) {
                ChannelKeys.LIST_REQUEST -> ListSyncCoordinator.onHostListRequest(this, from)
                ChannelKeys.LIST_VERIFY_REPLY -> ListSyncCoordinator.onHostVerifyReply(this, from, value)
            }
        }
        IslandListManager.init(this)
    }
}
