# ============================================================================
# ProGuard / R8 规则（release 未开启 minify，保留备用）
# ============================================================================

# ---- jcifs-ng（SMB）：反射加载配置类，必须保留 ----
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---- sardine-android（WebDAV）：simple-xml 基于注解 + 反射 ----
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-dontwarn com.thegrizzlylabs.sardineandroid.**
-keep class org.simpleframework.** { *; }
-dontwarn org.simpleframework.**
-dontwarn javax.xml.stream.**
-dontwarn javax.naming.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- Room ----
-keep class androidx.room.** { *; }

# ---- 数据模型（Room 实体 / 序列化配置） ----
-keep class com.nasphoto.migrator.data.** { *; }
-keep class com.nasphoto.migrator.business.model.** { *; }
