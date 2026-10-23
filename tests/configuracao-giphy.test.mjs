import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {abrirConfiguracaoGiphy} from "../packages/converter-core/configuracao-giphy.mjs";
const caso=JSON.parse(readFileSync(new URL("../packages/converter-core/giphy-caso.json",import.meta.url),"utf8").replace(/^\uFEFF/,""));
test("cifra compartilhada abre a configuração fictícia sem conter o texto claro",async()=>{
  assert.equal(caso.protegido.includes(caso.claro),false);
  assert.equal(await abrirConfiguracaoGiphy(caso.protegido),caso.claro);
});
test("configuração adulterada ou truncada falha fechada",async()=>{
  const partes=caso.protegido.split(".");
  partes[2]=(partes[2][0]==="A"?"B":"A")+partes[2].slice(1);
  for(const valor of [partes.join("."),"v1.invalido",caso.protegido.slice(0,-5),"<script>",""]) assert.equal(await abrirConfiguracaoGiphy(valor),"");
});
