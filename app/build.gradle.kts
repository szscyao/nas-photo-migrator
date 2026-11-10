// ============================================================================
// app 模块构建脚本
// ----------------------------------------------------------------------------
// 关键约束（来自阶段1文档，不可修改）：
//   * minSdk 29（Android 10），targetSdk 35
//   * 纯本地工具：无云端、无账号、不收集数据（无 crash 上报/统计 SDK）
//   * 协议：SMB/CIFS（jcifs-ng）+ WebDAV（sardine-android，JitPack）
//   * 密码：androidx.security EncryptedSharedPreferences（AES256-GCM + Keystore）
// ============================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nasphoto.migrator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nasphoto.migrator"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjvm-default=all")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // jcifs-ng / bouncycastle / simple-xml 等 Java 库自带 META-INF，需排除避免打包冲突
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/**",
                "META-INF/services/javax.xml.stream.*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Room 导出的 schema 位置（便于后续表结构升级审阅）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---------------- Kotlin / 协程 ----------------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---------------- AndroidX 基础 ----------------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // ---------------- Compose UI ----------------
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---------------- Room（已完成记录 / 失败记录 / 日志） ----------------
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---------------- 加密存储（NAS 密码） ----------------
    implementation("androidx.security:security-crypto:1.1.0")

    // ---------------- SMB/CIFS（SMB2/SMB3） ----------------
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") {
        // jcifs-ng 的 POM 里带 log4j12 bridge / servlet-api，Android 上会导致打包或运行问题
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet", module = "javax.servlet-api")
        exclude(group = "javax.annotation", module = "javax.annotation-api")
    }
    // jcifs-ng 使用 slf4j-api 1.7.36，Android 侧用官方 no-op 实现，避免运行时告警
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // ---------------- WebDAV ----------------
    // 注意：sardine-android 只发布在 JitPack（已在 settings.gradle.kts 配置仓库）
    implementation("com.github.thegrizzlylabs:sardine-android:0.9") {
        exclude(group = "org.simpleframework", module = "simple-xml")
    }
    // sardine 需要 simple-xml 解析 WebDAV multistatus（显式引入以控制版本/排除冲突）
    implementation("org.simpleframework:simple-xml:2.7.1") {
        exclude(group = "stax", module = "stax")
        exclude(group = "stax", module = "stax-api")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---------------- 单元测试（纯 JVM 逻辑：fileKey / 校验 / 状态机） ----------------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
