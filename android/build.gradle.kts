// Android 16: Gradle 9/AGP 9 e Kotlin integrado, com o compilador Compose alinhado.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21") }
}
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
