# Regras do R8 para o app para Android.
# JNI acessa estes campos e classes por nome, inclusive no APK otimizado.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# O JNI procura a sobrecarga invoke([F)Ljava/lang/Integer; do callback concreto.
-keep class br.com.smellsliketech.converter.conversao.KokoroCallback { *; }

# A instrumentação assinada testa o APK de publicação, não uma cópia sem otimização.
# Preserva a API dos motores e os contratos Kotlin chamados pelo APK de testes.
# A interface Compose e as demais dependências continuam otimizadas pelo R8.
-keep class br.com.smellsliketech.converter.conversao.** { *; }
-keep class br.com.smellsliketech.converter.estudio.** { *; }
-keep class br.com.smellsliketech.converter.ui.PlayerImediato { *; }
-keep class br.com.smellsliketech.converter.figurinhas.Producao** { *; }
-keep class br.com.smellsliketech.converter.figurinhas.Regras { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.** { *; }

# PDFBox: as regras de reflexão vêm do proguard.txt da própria biblioteca.
# Não manter pacotes inteiros: isso impedia remover codificadores nunca usados.
# Partes opcionais do PdfBox que o app não usa (JPEG 2000, criptografia do BouncyCastle).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# ML Kit (remoção de fundo e OCR): o Firebase Components cria cada registrador por reflexão,
# pelo nome que está no manifesto, com o construtor sem argumentos. Sem esta regra o R8 tirava
# o construtor, o ML Kit não iniciava e a remoção automática de fundo dava NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
