plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("app.wordgame.trainer.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

// ./gradlew :trainer:run --args="baseline 400" | "train 20000 models/value-v1.txt" | "eval models/value-v1.txt 1000"
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
