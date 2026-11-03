# R8 配置（release 构建）
#
# 本 App 的依赖面很窄：没有 Gson / Moshi / Jackson、没有 kotlin-reflect、
# 没有按类名加载（Class.forName）、没有 WebView 的 JS 桥、没有 Parcelable 自定义序列化。
# JSON 一律走 Android 平台自带的 org.json（非反射式实现），解析器是自研的 XlsxReader/DocxReader，
# ViewBinding 走 findViewById，因此不需要整包 keep，也不需要 keep 模型类字段。
#
# 唯一被反射按名字访问的是 View 的 protected 滚动进度方法（见下），其余交给 R8 默认行为。

# InkOverlay 用反射读取滚动进度：View 没有公开 API，只能拿 protected 的
# computeVerticalScrollOffset/Extent/Range，再 invoke 在 RecyclerView / NestedScrollView 实例上走虚分派。
# 这些子类重写的方法必须留下，否则分派会退回 View 的空实现，笔迹跟随滚动的偏移会算成 0。
-keepclassmembers class * {
    int computeVerticalScrollOffset();
    int computeVerticalScrollExtent();
    int computeVerticalScrollRange();
}

# 保留行号，便于 release 崩溃栈配合 mapping.txt 定位；源码文件名统一符号化，不泄露本地路径
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
