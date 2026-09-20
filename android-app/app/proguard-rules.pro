# ============================================================
# Asiiya 工作台 · R8 规则
# ============================================================

# ---- 崩溃栈可读：保留行号，隐藏原始文件名 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, MethodParameters

# ---- 从 XML 布局膨胀的自定义 View ----
# ShadowLayout / RingView / SparkView 由 XML 反射实例化，构造函数必须保留
-keepclasseswithmembers class com.dsh.console.** {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class com.dsh.console.** {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---- 桌面小组件：RemoteViews 通过类名反射实例化 ----
-keep class com.dsh.console.WidgetProvider { *; }

# ---- ViewBinding 静态入口 ----
-keepclassmembers class com.dsh.console.databinding.** {
    public static *** inflate(...);
    public static *** bind(...);
}

# ---- WebView JS 桥（当前未使用，预留避免将来踩坑）----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 枚举 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
