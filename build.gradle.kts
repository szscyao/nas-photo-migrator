// ============================================================================
// 根构建脚本：只声明插件版本，具体配置在 app/build.gradle.kts
// 版本锁定（均已验证存在）：
//   AGP 8.7.3  / Kotlin 2.0.21 / Compose Compiler 2.0.21 / KSP 2.0.21-1.0.28
// ============================================================================
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
