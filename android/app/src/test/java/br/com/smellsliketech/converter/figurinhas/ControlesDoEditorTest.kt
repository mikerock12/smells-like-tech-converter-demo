package br.com.smellsliketech.converter.figurinhas

import org.junit.Assert.*
import org.junit.Test

class ControlesDoEditorTest {
    @Test fun caixasAninhadasMapeiamOPincelSemMudarAEdicao() {
        val primeira = JanelaDaPrevia().caixa(.25f, .25f, .75f, .75f)
        assertEquals(.5f, primeira.lado, .00001f)
        assertEquals(.5f, primeira.ponto(.5f, .5f).first, .00001f)
        val segunda = primeira.caixa(0f, 0f, .5f, .5f)
        assertEquals(.25f, segunda.lado, .00001f)
        assertEquals(.375f, segunda.ponto(.5f, .5f).second, .00001f)
        assertEquals(primeira, JanelaDaPrevia().caixa(.75f, .75f, .25f, .25f))
        val limite = segunda.ampliar(1000f)
        assertEquals(1f / 16, limite.lado, 0f)
        assertEquals(JanelaDaPrevia(), limite.ampliar(.001f))
    }
    @Test fun inclinacaoUsaOEixoCorretoENaoMoveAImagem() {
        val inicial = Edicao(deslocamentoX = .2f, giro = 30)
        val horizontal = GestoDeInclinacao.aplicar(inicial, 50f, 0f, 100, 100)
        assertEquals(90f, horizontal.inclinacaoY, 0f)
        assertEquals(0f, horizontal.inclinacaoX, 0f)
        assertEquals(inicial.deslocamentoX, horizontal.deslocamentoX, 0f)
        assertEquals(30, horizontal.giro)
        assertEquals(-180f, GestoDeInclinacao.aplicar(inicial, 0f, -999f, 100, 100).inclinacaoX, 0f)
    }
    @Test fun gestoInteiroDesfazDeUmaVezENovaEdicaoDescartaORefazer() {
        val h = HistoricoDeEdicao<Int>()
        h.iniciar(); h.registrar(0); h.registrar(1); h.registrar(2); h.terminar()
        assertEquals(0, h.desfazer(3))
        assertFalse(h.podeDesfazer)
        assertEquals(3, h.refazer(0))
        assertEquals(0, h.desfazer(3))
        h.registrar(0)
        assertFalse(h.podeRefazer)
    }
    @Test fun memoriaECoalescenciaSaoLimitadas() {
        val h = HistoricoDeEdicao<Int>(peso = { 10 }, limite = 20)
        h.registrar(0,"zoom",1000); h.registrar(1,"zoom",1100)
        assertEquals(0,h.desfazer(2))
        h.limpar()
        repeat(8) {h.registrar(it)}
        assertEquals(7,h.desfazer(8));assertEquals(6,h.desfazer(7));assertNull(h.desfazer(6))
    }
}
