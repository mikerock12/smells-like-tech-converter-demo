import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
test('builds compartilham configuração DPAPI e restauram o ambiente, usando somente chave fictícia',{skip:process.platform!=='win32'},()=>{
  const resultado=spawnSync('pwsh',['-NoProfile','-File','tests/giphy-ambiente.Tests.ps1'],{encoding:'utf8',timeout:30000});
  assert.equal(resultado.status,0,resultado.stderr);
  assert.match(resultado.stdout,/aprovados/);
});
