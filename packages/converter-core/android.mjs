/**
 * O app para Android sai só pela Google Play (decidido em 30/09/2026): o site não oferece
 * o APK para baixar, e a licença do app é comprada dentro dele, pelo Google Play.
 *
 * Enquanto o teste fechado exigido pela Play não termina (12 testadores por 14 dias, ver
 * docs/play-store.md), `ANDROID_NA_PLAY` fica falso e o site diz que o app chega em breve.
 * No dia em que a página da loja estiver publicada, basta virar para verdadeiro: /android
 * mostra o selo "Disponível no Google Play" e /precos passa a apresentar o app.
 */
export const ANDROID_NA_PLAY = false;

export const PACOTE_DO_ANDROID = "br.com.smellsliketech.converter";

export const LINK_DA_GOOGLE_PLAY = `https://play.google.com/store/apps/details?id=${PACOTE_DO_ANDROID}`;
