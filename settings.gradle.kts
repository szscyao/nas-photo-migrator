// ============================================================================
// 相册逐张边传边删迁移NAS工具 —— Gradle 设置
// 仓库顺序：google() -> mavenCentral() -> jitpack.io
//   * jitpack.io 仅用于 sardine-android（WebDAV 库，官方只发布到 JitPack）
// ============================================================================
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "NasPhotoMigrator"
include(":app")
