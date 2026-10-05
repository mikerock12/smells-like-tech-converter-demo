import { defineConfig, globalIgnores } from "eslint/config";
import eslint from "@eslint/js";
import next from "@next/eslint-plugin-next";
import jsxA11y from "eslint-plugin-jsx-a11y";
import react from "eslint-plugin-react";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

const eslintConfig = defineConfig([
  globalIgnores([
    ".claude/**",
    "outputs/**",
    "packages/kokoro/model/**",
    "packages/kokoro/model-web/**",
    "desktop/artifacts/**",
    "desktop/**/bin/**",
    "desktop/**/obj/**",
    "android/.gradle/**",
    "android/.kotlin/**",
    "android/**/build/**",
    "android/artifacts/**",
    ".next/**",
    "dist/**",
    "out/**",
    "build/**",
    // Estado e bundles temporários do wrangler (wrangler dev, R2/D1 locais): ignorados pelo git, não são código nosso.
    ".wrangler/**",
    "next-env.d.ts",
    "public/motores/**",
  ]),
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  react.configs.flat.recommended,
  react.configs.flat["jsx-runtime"],
  reactHooks.configs.flat["recommended-latest"],
  jsxA11y.flatConfigs.recommended,
  next.configs["core-web-vitals"],
  {
    // As páginas são componentes de servidor no vinext, e o <Link> do Next usa hooks:
    // renderizado de lá ele derruba a página com "Invalid hook call". Um <a> comum
    // navega igual e não custa JavaScript nenhum.
    rules: { "@next/next/no-html-link-for-pages": "off" },
  },
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
        ...globals.serviceworker,
      },
    },
    settings: {
      react: {
        version: "detect",
      },
    },
  },
]);

export default eslintConfig;
