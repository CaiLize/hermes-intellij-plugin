plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // Coroutines are provided by the IntelliJ Platform — do NOT bundle them
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.25")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
    // 排除错误的目录 com/hermes/model/（重复的 Conversation.kt）
    sourceSets.all {
        kotlin.srcDirs("src/main/kotlin")
        kotlin.exclude("**/com/hermes/model/**")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        description = """
            Hermes AI Assistant - Connect to your locally deployed Hermes Agent for AI-powered coding assistance.
            Features include chat panel, code context integration, and code operations.
        """.trimIndent()
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    wrapper {
        gradleVersion = "8.14.4"
    }

    // 修复 WSL2 环境下 buildSearchableOptions 任务失败的问题
    buildSearchableOptions {
        enabled = false
    }

    // 构建完成后自动将分发包复制到 release/ 目录
    val releaseDir = layout.projectDirectory.dir("release")
    val copyToRelease by registering(Copy::class) {
        description = "Copy plugin distributable to release/"
        from(layout.buildDirectory.dir("distributions")) {
            include("*.zip")
        }
        into(releaseDir)
    }
    buildPlugin {
        finalizedBy(copyToRelease)
    }
}
