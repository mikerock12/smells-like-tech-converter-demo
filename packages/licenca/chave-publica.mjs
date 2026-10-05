/**
 * A chave pública das licenças (SPKI, base64url), gerada em 24/09/2026 com
 * `npm run licencas:gerar`.
 *
 * Pública de propósito: ela só verifica assinaturas, não as produz. A privada
 * correspondente existe em dois lugares — o segredo LICENCA_CHAVE_PRIVADA do Worker e
 * um arquivo criptografado guardado fora do repositório — e em nenhum outro.
 *
 * O aplicativo e o plugin embutem a mesma chave em LicenseVerifier.cs; um teste confere
 * que os dois valores batem. Gerar um par novo é trocar aqui, lá e no segredo do Worker;
 * as chaves já emitidas continuam válidas enquanto a antiga ficar embutida.
 */
export const CHAVE_PUBLICA_DAS_LICENCAS = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw7i4eDfAptC3Fl8WTK9sEz7-w8LATA7-gJbf0eo0_WEax7hi5Jp1o5gIghd7tCHzrMNUAycDGeiLD5yG9ojZ0A";
