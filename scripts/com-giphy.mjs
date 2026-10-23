/** Injeta a configuração local só no processo filho. Linux/CI usam o ambiente fornecido. */
import {spawnSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {homedir} from 'node:os';
const [comando,...argumentos]=process.argv.slice(2);
if(!comando) throw new Error('Informe o comando do build.');
const local=process.platform==='win32'&&existsSync(resolve(homedir(),'.smellsliketech/catalogos/giphy.dpapi.json'));
const resultado=local
 ? spawnSync('pwsh',['-NoProfile','-File',resolve('scripts/giphy-ambiente.ps1'),'-Comando',comando,...argumentos],{stdio:'inherit'})
 : spawnSync(comando,argumentos,{stdio:'inherit'});
if(resultado.error) {console.error('Não foi possível iniciar o build com a configuração local.');process.exitCode=1;}
else process.exitCode=resultado.status??1;
