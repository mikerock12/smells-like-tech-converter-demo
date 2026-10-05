// O app para Android: o conversor completo no celular, sem site e sem plugin.
// Versões escolhidas para bater com o Gradle 8.14 e o Android Gradle Plugin 8.13, o
// primeiro a compilar para o Android 16 (API 36), que a Google Play exige desde 31/08/2026.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
