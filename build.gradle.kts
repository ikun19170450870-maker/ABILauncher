import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.compose") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.1"
}

group = "com.abi"
version = "1.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Main.kt 使用 androidx.compose.material3，currentOs 并不包含它，必须显式声明
    implementation(compose.material3)
    // 解析 versions/<id>/<id>.json 与资源索引
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    // Gradle 9 起必须显式声明 junit-platform-launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// 命令行识别，便于没有图形界面时验证结果：
//   gradlew scan -PscanPath="C:\Users\你\AppData\Roaming\.minecraft"
tasks.register<JavaExec>("scan") {
    group = "application"
    description = "在指定目录中识别 Minecraft 版本（-PscanPath=<目录>）"
    mainClass.set("com.abi.launcher.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("scan", providers.gradleProperty("scanPath").getOrElse(""))
}

// 命令行启动，dryRun=true 时只打印将要执行的命令：
//   gradlew launch -Pinstall="D:\zawu2\.minecraft" -Pversion="1.21.8" -PdryRun=true
tasks.register<JavaExec>("launch") {
    group = "application"
    description = "启动指定版本（-Pinstall=<游戏目录> -Pversion=<版本号> [-PdryRun=true] [-Pplayer=名字] [-Pmemory=2048]）"
    mainClass.set("com.abi.launcher.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = buildList {
        add("launch")
        add(providers.gradleProperty("install").getOrElse(""))
        add(providers.gradleProperty("version").getOrElse(""))
        if (providers.gradleProperty("dryRun").getOrElse("false").equals("true", ignoreCase = true)) {
            add("--dry-run")
        }
        providers.gradleProperty("player").orNull?.let { add("--player"); add(it) }
        providers.gradleProperty("memory").orNull?.let { add("--memory"); add(it) }
    }
}

compose.desktop {
    application {
        mainClass = "com.abi.launcher.MainKt"

        nativeDistributions {
            // 注意：Compose 插件在 Windows 上执行任何打包任务前，都会先去 github.com
            // 下载 WiX（任务名 downloadWix / unzipWix）。网络不通时连 createDistributable
            // 都会失败。免安装版用下面的 portableImage 任务打，完全不经过 WiX。
            //
            // 想打 Msi / Exe 安装包时：装好 WiX Toolset 3.x（candle.exe / light.exe），
            // 再把 TargetFormat.Msi / TargetFormat.Exe 加回下面这行。
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ABILauncher"
            packageVersion = "1.0.0"
            description = "ABI Minecraft 启动器"
            vendor = "ABI"
            copyright = "© 2026 ABI"

            // 下面这些只在打 Msi / Exe 安装包时生效
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "8B0D3A3E-6D1C-4C2E-9E58-2E0F0B4A7C31"
            }
        }
    }
}

// ================================================================ 免安装版打包
//
//   gradlew portableImage
//
// 产物：build\portable\out\ABILauncher\ABILauncher.exe
//       整个 ABILauncher 文件夹就是绿色版（自带 Java 运行时），拷到别的机器即可运行。
//
// 它做的事：把所有依赖 jar 收进一个目录 → 给主 jar 的清单写上 Class-Path →
//          调用 JDK 自带的 jpackage 生成 app-image。全程不需要 WiX。

// 主 jar 的清单里写入 Class-Path，jpackage 生成的启动器才能找到依赖 jar
tasks.jar {
    val runtimeClasspath = configurations.runtimeClasspath
    manifest {
        attributes("Class-Path" to runtimeClasspath.get().joinToString(" ") { it.name })
    }
}

val portableInputDir = layout.buildDirectory.dir("portable/input")
val portableOutputDir = layout.buildDirectory.dir("portable/out")

val preparePortableInput by tasks.registering(Sync::class) {
    group = "compose desktop"
    description = "把主 jar 与全部依赖 jar 收集到 build/portable/input"
    into(portableInputDir)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
}

val portableImage by tasks.registering(Exec::class) {
    group = "compose desktop"
    description = "免安装版打包（不需要 WiX）：build/portable/out/ABILauncher/ABILauncher.exe"
    dependsOn(preparePortableInput)

    val inputDir = portableInputDir
    val outputDir = portableOutputDir
    val mainJarName = tasks.jar.flatMap { it.archiveFileName }

    doFirst {
        // 每次重新生成，避免残留旧文件
        outputDir.get().asFile.deleteRecursively()

        val javaHome = System.getProperty("java.home")
        val jpackage = if (System.getProperty("os.name").lowercase().contains("win")) {
            "$javaHome\\bin\\jpackage.exe"
        } else {
            "$javaHome/bin/jpackage"
        }

        commandLine(
            jpackage,
            "--type", "app-image",
            "--name", "ABILauncher",
            "--app-version", "1.0.0",
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", mainJarName.get(),
            "--main-class", "com.abi.launcher.MainKt",
            "--dest", outputDir.get().asFile.absolutePath,
            "--vendor", "ABI",
            "--description", "ABI Minecraft 启动器",
            // 让 jpackage 自己 jlink 运行时：不要传 --runtime-image。
            // 传了的话生成的 app/*.cfg 里不会写 app.runtime，启动器找不到 JVM 会静默退出（退出码 0）。
            // 模块列表参考 Compose 插件自己算出来的（createRuntimeImage.args.txt），
            // 另加 jdk.unsupported / java.naming 作为保险（Skiko、JNA 之类会用到）。
            // java.net.http：下载/Modrinth 用 HttpClient，JDK 11 起它是独立模块，漏了就会
            //   一点「下载」就报 java/net/http/HttpClient（开发模式用完整 JDK，所以看不出来）。
            // jdk.charsets：保险，让非 UTF-8 的路径/文件名也能处理。
            "--add-modules",
            "java.base,java.desktop,java.logging,java.naming,java.net.http,jdk.crypto.ec,jdk.unsupported,jdk.charsets",
            // exe 图标（Windows 只认 .ico，PNG 不行）
            "--icon",
            project.file("icon.ico").absolutePath,
        )
    }
}
