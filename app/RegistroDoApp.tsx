"use client";

import { useEffect } from "react";

import { iniciarApp } from "@/lib/pwa/app";

/** Registra o service worker e guarda o convite de instalação. Não desenha nada. */
export default function RegistroDoApp() {
  useEffect(() => {
    iniciarApp();
  }, []);
  return null;
}
