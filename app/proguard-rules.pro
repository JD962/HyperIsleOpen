# ===== libxposed 现代 API（API 102）入口与 Hooker 必须保留 =====
# 入口类名写入 META-INF/xposed/java_init.list，R8 若混淆该类名将导致模块无法加载。
-keep class com.island.pass.IslandModuleEntry { *; }
-keep class com.island.pass.hook.SystemUiHooker { *; }
-keep class com.island.pass.hook.NoticeFlowHooker { *; }

# Hook 逻辑通过字符串反射目标进程类名，模块自身混淆无碍，
# 但保留 hook 包可读性便于抓 log 定位问题。
-keep class com.island.pass.hook.** { *; }

# ===== libxposed 官方规则（来自官方示例模块）=====
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# ===== HookTool（LGPL-2.1，HChenX）=====
-keep class com.hchen.hooktool.** { *; }
-dontwarn com.hchen.hooktool.**

# ===== 反射属性 =====
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# ===== 配置数据结构 =====
-keep class com.island.pass.data.IslandConfig { *; }
