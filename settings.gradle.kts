// 仓库顺序原则：国内镜像优先，但「Google 镜像必须排在第一位」。
//
// 踩过的坑：maven.aliyun.com/repository/gradle-plugin 只代理 Gradle Plugin Portal，
// 不含 Google 的构件（com.android.tools:* 在它上面是 404），且该镜像偶发 502。
// 一旦它排在首位并返回 5xx，Gradle 会把这个仓库在本次构建内直接标记为 disabled，
// 之后所有构件解析连锁失败——报错却指向毫不相干的 kotlin-reflect。
// 所以：google 镜像打头，gradle-plugin 压到最后只作 Plugin Portal 的兜底。

pluginManagement {
    repositories {
        // AGP 及其所有 com.android.tools:* 都只有 Google 系仓库才有
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // kotlin plugin marker、kotlin-reflect 等在 Maven Central
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        // 只兜底 Plugin Portal 独有的插件，放最后，出问题也不影响主流程
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "QBank"
include(":app")
