/** Somente pesos públicos licenciados. Nenhuma entrada de usuário chega a esta rota. */
const MODELO = "https://github.com/mikerock12/smells-like-tech-converter-demo/releases/download/kokoro-82m-ptbr-v1/kokoro-82m-v1-fp32.zip";
export async function GET(): Promise<Response> {
  const resposta = await fetch(MODELO, { redirect: "follow" });
  if (!resposta.ok) return new Response("Modelo temporariamente indisponível. Tente novamente.", { status: 503 });
  return new Response(resposta.body, {
    headers: { "Content-Type": "application/zip", "Cache-Control": "public, max-age=31536000, immutable", "X-Content-Type-Options": "nosniff" },
  });
}
