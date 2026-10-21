plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("app.wordgame.dict.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

// ./gradlew :dict-builder:run --args="<lexique-grammalecte.txt> <output-dir>"
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
