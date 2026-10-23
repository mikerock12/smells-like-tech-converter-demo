package br.com.smellsliketech.converter.estudio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CriacaoTest {
    private fun projeto(arquivo: String)=Projeto(largura=64,altura=64,fps=10,clipes=listOf(Clipe("c",arquivo,"imagem",duracao=5.0,ajuste="caber")))
    @Test fun `animacao interpola sem saltos e preserva as pontas`() {
        val t=Transformacao(quadrosChave=listOf(QuadroChave(1.0,0.0,.25,1.0,"suave"),QuadroChave(3.0,1.0,.75,2.0)))
        assertEquals(.15625,Animacao.noTempo(t,1.5).x,0.0);assertEquals(.5,Animacao.noTempo(t,2.0).x,0.0);assertNull(t.problema())
        assertNotNull(t.copy(quadrosChave=listOf(QuadroChave(0.0,0.0,0.0,0.0))).problema())
        assertEquals(t,Transformacao.deJson(t.paraJson()))
        val movida=Animacao.mudarBase(t,t.copy(x=.6,y=.4,escala=2.0));val ponto=Animacao.noTempo(movida,2.0)
        assertEquals(.6,ponto.x,1e-8);assertEquals(.4,ponto.y,1e-8);assertEquals(3.0,ponto.escala,1e-8)
        assertEquals("suave",movida.quadrosChave[0].curva)
    }
    @Test fun `contagem modelos corte e migracao preservam a composicao`() {
        val p=projeto("a");val c=Criacao.contagem(p,1.0,3)
        assertEquals(listOf("2"),Estudio.textosNoTempo(c,2.0).map {it.texto});assertTrue(Estudio.textosNoTempo(c,4.0).isEmpty())
        assertEquals(2,Criacao.modelo(p,"fotos").clipes[0].transformacao!!.quadrosChave.size)
        val camada=Camada("l","b","video",1.0,6.0,2.0,transformacao=Animacao.kenBurns(duracao=5.0))
        val dividido=Criacao.dividirCamada(p.copy(camadas=listOf(camada)),"l",2.0,"l2")
        assertEquals(3.0,dividido.camadas[1].deslocamento,0.0)
        assertEquals(Animacao.noTempo(camada.transformacao!!,1.5).escala,Animacao.noTempo(dividido.camadas[1].transformacao!!,.5).escala,0.0)
        val antigo=p.copy(versao=3,audios=listOf(AudioExtra("a","m",entrada=1.0,inicio=2.0,fim=20.0)))
        assertEquals(5.0,Estudio.duracaoTotal(Criacao.atualizar(antigo)),0.0)
    }
    @Test fun `arquivo portatil inclui midias e recusa caminho externo`() {
        val pasta=Files.createTempDirectory("studio-arquivo-").toFile()
        try {
            val original=File(pasta,"original.png").apply {writeBytes(byteArrayOf(1,2,3,4))};val zip=File(pasta,"projeto.sltstudio")
            zip.outputStream().use {ArquivoDoProjeto.salvar(projeto(original.absolutePath),it)}
            val aberto=zip.inputStream().use {ArquivoDoProjeto.abrir(it,File(pasta,"reaberto"),File(pasta,"entrada.zip"))}
            assertArrayEquals(original.readBytes(),File(aberto.clipes[0].arquivo).readBytes());assertEquals(4,aberto.versao)
            val ruim=File(pasta,"ruim.zip");ZipOutputStream(ruim.outputStream()).use {it.putNextEntry(ZipEntry("../fora.txt"));it.write(byteArrayOf(1));it.closeEntry()}
            assertThrows(IllegalArgumentException::class.java) {ruim.inputStream().use {ArquivoDoProjeto.abrir(it,File(pasta,"recusado"),File(pasta,"temporario.zip"))}}
            assertFalse(File(pasta,"recusado").exists());assertFalse(File(pasta,"fora.txt").exists())
        } finally {pasta.deleteRecursively()}
    }
}
