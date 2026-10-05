# Só no APK de testes: anotação de compilação do Error Prone referencia uma API do JDK,
# ausente no Android. Não é usada pela execução dos testes nem pelo aplicativo oficial.
-dontwarn javax.lang.model.element.Modifier
