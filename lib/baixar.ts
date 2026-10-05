import metadadosDoAplicativo from "@/public/baixar/app.json";
import metadadosDoPlugin from "@/public/baixar/plugin.json";

/**
 * De onde o site tira os instaladores para baixar.
 *
 * Os JSON são importados, e não lidos do disco em tempo de execução, porque o site
 * roda num Worker da Cloudflare — lá não existe sistema de arquivos. Importando, o
 * conteúdo entra no pacote durante o build e chega pronto.
 *
 * Os .exe em si não são versionados: são dezenas de megabytes por versão, e o
 * repositório carregaria todas para sempre. Eles são gerados por
 * `desktop/installer/build-installer.ps1` (aplicativo) e
 * `build-plugin-installer.ps1` (plugin), que também gravam estes JSON.
 *
 * Também não ficam em public/: passam do limite de 25 MiB dos assets do Worker. Vão
 * para um bucket R2 (`npm run instaladores:publicar`), e o Worker os entrega no mesmo
 * `/baixar/<arquivo>` de sempre — ver worker/instaladores.ts.
 *
 * São dois produtos separados e com ciclos próprios: o plugin pode ficar parado
 * numa versão enquanto o aplicativo anda, e vice-versa. Por isso são dois arquivos,
 * e não um só com dois campos.
 */

export type ParaBaixar = {
  versao: string;
  arquivo: string;
  bytes: number;
  sha256: string;
  publicado: string;
  endereco: string;
};

type Metadados = Omit<ParaBaixar, "endereco">;

export function lerPluginParaBaixar(): ParaBaixar | null {
  return montar(metadadosDoPlugin, process.env.NEXT_PUBLIC_URL_DO_PLUGIN);
}

export function lerAplicativoParaBaixar(): ParaBaixar | null {
  return montar(metadadosDoAplicativo, process.env.NEXT_PUBLIC_URL_DO_APLICATIVO);
}

/**
 * Um instalador que ainda não foi gerado tem JSON vazio. Devolver nulo faz a página
 * dizer isso em vez de oferecer um link quebrado.
 */
function montar(metadados: Partial<Metadados>, base: string | undefined): ParaBaixar | null {
  if (!metadados.arquivo || !metadados.versao) {
    return null;
  }

  return { ...(metadados as Metadados), endereco: enderecoDe(metadados.arquivo, base) };
}

/**
 * Endereço do arquivo. Por padrão, o do próprio site, onde o Worker o busca no R2. Se um
 * dia os instaladores forem servidos de outro lugar (um domínio público do bucket, por
 * exemplo), basta apontar a variável — sem mexer no código.
 */
function enderecoDe(arquivo: string, base: string | undefined): string {
  return base ? `${base.replace(/\/$/, "")}/${arquivo}` : `/baixar/${arquivo}`;
}

export function emMegabytes(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1).replace(".", ",")} MB`;
}
