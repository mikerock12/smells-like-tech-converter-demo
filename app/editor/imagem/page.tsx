import { SiteHeader } from "../../SiteChrome";
import Editor from "./Editor";
import "./imagem.css";

export const metadata = { title: "Estúdio de imagem | Smells Like Tech Converter" };

export default function Pagina() {
  return <><SiteHeader page="estudio" /><Editor /></>;
}
