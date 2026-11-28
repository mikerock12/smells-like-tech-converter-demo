plugins { id("com.android.asset-pack") }

assetPack {
    packName.set("ampliador_slti")
    dynamicDelivery { deliveryType.set("on-demand") }
}

// O pack sob demanda da Play recebe apenas o modelo 2× autorizado pelo titular da SLTI.
val fonte = rootProject.file("../laboratorio/imagem/modelos/ampliador-2x.json")
val destino = layout.projectDirectory.file("src/main/assets/ampliador-2x.json").asFile
val copiarPesos = tasks.register("prepararAmpliadorSlti") {
    inputs.file(fonte)
    outputs.file(destino)
    doLast {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(fonte.readBytes())
        check(java.util.HexFormat.of().formatHex(hash) == "1905ec3cc0ab29ea395326c2362f1466cb92bf8211e4aa5769308489c4e00dc3")
        destino.parentFile.mkdirs()
        fonte.copyTo(destino, overwrite = true)
    }
}
tasks.matching { it.name.contains("AssetPack") }.configureEach { dependsOn(copiarPesos) }
