# Regras do R8 para o app para Android.
# JNI acessa estes campos e classes por nome, inclusive no APK otimizado.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# O JNI procura a sobrecarga invoke([F)Ljava/lang/Integer; do callback concreto.
-keep class br.com.smellsliketech.converter.conversao.KokoroCallback { *; }

# A instrumentação assinada testa o APK de publicação, não uma cópia sem otimização.
# Preserva a API dos motores e os contratos Kotlin chamados pelo APK de testes.
# A interface Compose e as demais dependências continuam otimizadas pelo R8.
-keep class br.com.smellsliketech.converter.conversao.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.** { *; }

# PdfBox-Android lê fontes e tabelas por nome e usa reflexão em alguns pontos: fica inteiro.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
# Partes opcionais do PdfBox que o app não usa (JPEG 2000, criptografia do BouncyCastle).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
