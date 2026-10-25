plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.island.pass"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.island.pass"
        // 版本限定：仅支持 Android 15（API 35）/ Android 16（API 36）
        minSdk = 35
        maxSdk = 36
        targetSdk = 36
        versionCode = 123
        versionName = "1.0.23"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 开源仓库不含签名密钥：仅当 app/qingyun.jks 存在且签名属性就绪时才配置
            // release 签名（属性经用户级 ~/.gradle/gradle.properties 提供，不入库）。
            // 他人克隆后直接 assembleRelease 得到未签名包，放入自有 jks 即可签名。
            val keystoreFile = file(projectDir.resolve("qingyun.jks"))
            val storePassword = providers.gradleProperty("signing.storePassword").orNull
            val keyAlias = providers.gradleProperty("signing.keyAlias").orNull
            val keyPassword = providers.gradleProperty("signing.keyPassword").orNull
            signingConfigs {
                if (keystoreFile.exists() && storePassword != null && keyAlias != null && keyPassword != null) {
                    create("release") {
                        storeFile = keystoreFile
                        this.storePassword = storePassword
                        this.keyAlias = keyAlias
                        this.keyPassword = keyPassword
                    }
                }
            }
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            // libxposed 模块注册文件必须打进 APK：java_init.list / module.prop / scope.list
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // libxposed 现代 API（API 102）：api 仅编译期可见，service 在模块 App 侧运行
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    // HookTool（HChenX）：基于 libxposed 的 Kotlin Hook DSL
    implementation(libs.hooktool)

    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose + Miuix
    implementation(platform(libs.compose.bom))
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.androidx.activity.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

base {
    // 输出 APK 命名为 HyperIsleOpen-<versionName>-<buildType>.apk
    archivesName.set("HyperIsleOpen")
}
