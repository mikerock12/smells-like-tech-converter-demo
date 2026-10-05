import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A assinatura de publicação mora fora do repositório e das worktrees, em
// ~/.smellsliketech/android/ (assinatura.properties + o .jks), com cópia criptografada no
// Drive. Perdê-la é não conseguir mais atualizar o app nos celulares dos clientes. Sem ela,
// o build de release sai sem assinatura; o de depuração e a prévia instalam normalmente.
val assinatura = Properties().apply {
    val arquivo = File(System.getProperty("user.home"), ".smellsliketech/android/assinatura.properties")
    if (arquivo.exists()) arquivo.inputStream().use(::load)
}

// Preenchida depois de criar o app no Play Console; o `npm run android:aab` recusa gerar a
// versão da Play sem ela, porque sem ela nenhuma compra seria reconhecida.
val chaveDaPlay = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3IGjUBKUseVN2TcmxhmnGoVPOEahBE6nW5ZsKgvdDbRaT27kkH3jFRsIQQLG1zood6JczEOW428M0/nt8t/39xp9bG6sxf5rEXHrqUdC/ic8lew8cQGnUxpFC7ar26ktK09iImgWAPyiRkMP1T2I489drlsZqL+OegizGQvnQODWW4CFqtrqIl1qmXIc/aOZj/wDddD+9abdvLOtYGQUEr7UkJjCw6BbTPgV6ZJVEUOwq4UgiWQ6c9b0AmX/5w7oIQXogoIteClcgm3nLBnWO5DjkaJ4J4qDOpz9B+vaTF0hbiteHdJ+rC4mfP+YceEBn8JC5xmp9UEo9HdC+ohixwIDAQAB"

android {
    // Permite verificar o APK oficial (com R8 e assinatura real) no emulador.
    testBuildType = if (providers.gradleProperty("testeRelease").orNull == "true") "release" else "debug"
    namespace = "br.com.smellsliketech.converter"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.smellsliketech.converter"
        // Android 10: salvar em Downloads pelo MediaStore sem permissão de armazenamento.
        minSdk = 29
        // A Google Play exige o Android 16 como alvo desde 31/08/2026.
        targetSdk = 36
        versionCode = 8
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // As arquiteturas do FFmpeg compilado (android/nativos): celulares ARM e, em x86_64,
        // Chromebooks e o emulador. A Google Play entrega a cada aparelho só a sua.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // O mesmo app, vendido de dois jeitos. "site": o APK baixado do site, com a licença
    // comprada lá (Mercado Pago) e colada no app. "play": a Google Play, onde a regra é
    // vender pelo faturamento do Google e não mandar o cliente comprar fora; a chave de
    // quem já comprou pelo site continua valendo. Mesmo pacote e mesma chave de assinatura:
    // um instala por cima do outro sem perder nada.
    flavorDimensions += "loja"
    productFlavors {
        create("site") {
            dimension = "loja"
            buildConfigField("boolean", "PELA_PLAY", "false")
            buildConfigField("String", "CHAVE_DA_PLAY", "\"\"")
        }
        create("play") {
            dimension = "loja"
            buildConfigField("boolean", "PELA_PLAY", "true")
            // A chave pública de licenciamento (Play Console → Monetização → Licenciamento).
            // Pública por natureza: confere os recibos das compras no celular.
            buildConfigField("String", "CHAVE_DA_PLAY", "\"$chaveDaPlay\"")
        }
    }

    signingConfigs {
        if (assinatura.isNotEmpty()) {
            create("publicacao") {
                storeFile = File(assinatura.getProperty("arquivo"))
                storePassword = assinatura.getProperty("senha")
                keyAlias = assinatura.getProperty("apelido")
                keyPassword = assinatura.getProperty("senha")
            }
        }
    }

    buildTypes {
        debug {
            // Testes reais não substituem a instalação oficial nem apagam a licença dela.
            applicationIdSuffix = ".teste"
        }
        release {
            // O R8 tira o que não é usado e encolhe o resto: o APK sai de ~85 MB para uma fração.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-test.pro")
            if (assinatura.isNotEmpty()) signingConfig = signingConfigs.getByName("publicacao")
        }
        // Igual ao de publicação, mas assinado com a chave de depuração: para instalar no
        // celular e testar antes da chave oficial. Não vai para os clientes (a assinatura é
        // outra, então o app de verdade não instala por cima dele sem desinstalar).
        create("previa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // O FFmpeg é um executável (libffmpeg.so): o Android precisa extraí-lo para a pasta
        // das bibliotecas nativas, onde o app tem permissão de executar.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Vídeo, áudio e transcrição: o FFmpeg compilado em android/nativos (npm run android:nativos).
    // OCR no aparelho, com o modelo embutido (funciona sem internet e sem Google Play).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // PDF: juntar, dividir, girar (o Android só sabe desenhar e criar PDF).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // HTML e EPUB (documentos): um leitor que aguenta HTML malformado.
    implementation("org.jsoup:jsoup:1.18.3")

    // A compra na versão da Google Play. Fala com a Play Store por IPC: quem acessa a rede é a
    // Play Store, não o app.
    "playImplementation"("com.android.billingclient:billing-ktx:9.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
