# TVBoxOS-Live ProGuard 规则

# 保留 Room 实体
-keep class com.github.tvbox.osc.bean.** { *; }
-keep class com.github.tvbox.osc.cache.** { *; }

# 保留 Gson 序列化
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# 保留 EventBus
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# 保留 OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# 保留 ExoPlayer（含通过反射使用的 ext.rtmp.RtmpDataSource$Factory 与 rtsp 模块）
-keep class com.google.android.exoplayer2.** { *; }

# RTMP 扩展的 JNI 桥接类：native 方法名不可混淆，否则运行时 UnsatisfiedLinkError
-keep class net.butterflytv.rtmp_client.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 Hawk
-keep class com.orhanobut.hawk.** { *; }

# 保留服务
-keep class com.github.tvbox.osc.service.** { *; }
