# ---- 发行版收缩规则 ----
#
# 目标：打包进 APK 的只有真正用得到的东西。
# 这里把几个**靠反射/名字工作**的库整个保住，其余交给 R8 正常裁剪。

# jaudiotagger：内部按扩展名挑解析器、还有一批按类名构造的帧体，整个包保下来最稳。
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
# 它引用了 Android 上不存在的 JDK 类，缺了会只是警告，不影响运行。
-dontwarn java.beans.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.awt.**

# QuickJS：宿主对象是通过 JNI + 反射绑定到 JS 里的，字段名不能改。
-keep class app.cash.quickjs.** { *; }
-keep class com.audiometa.app.plugin.runtime.** { *; }

# Room / 数据层：实体和 DAO 由 Room 生成的代码按名字访问。
-keep class com.audiometa.app.data.db.** { *; }
-keep class com.audiometa.app.data.prefs.** { *; }
-keep class com.audiometa.app.domain.model.** { *; }
-keep class com.audiometa.app.plugin.model.** { *; }

# 插件宿主 API：方法名就是暴露给 JS 的接口名。
-keep class com.audiometa.app.plugin.runtime.HostApi { *; }
-keep class com.audiometa.app.plugin.runtime.HostBridge { *; }

# 枚举的 values()/valueOf() 在设置里到处用（ThemeMode.valueOf 之类）。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ViewModel 由 ViewModelProvider **反射**创建（找 (Application) / 无参构造器）。
# R8 看不到这类"反射调用"，会把没人直接 new 的构造器裁掉或改名 ——
# release 包里一进主界面就 "Cannot create an instance of class ...ViewModel"。
# debug 包不混淆，所以这个坑只在发行包里出现。
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
-keep class androidx.lifecycle.ViewModelProvider$NewInstanceFactory { *; }
-keep class androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory { *; }

# 崩溃日志解析出来的版本号/异常类名不依赖反射，但保留异常构造器便于定位。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 用户手机上发生的崩溃，堆栈要能直接看懂。
# 只保留**名字**（不阻止裁剪），代价是几十 KB，换来的是崩溃对话框里
# 直接出现 com.audiometa.app.xxx.SettingsViewModel 而不是 U1.f1。
-keepnames class com.audiometa.app.**
-keepclassmembernames class com.audiometa.app.** { *; }
