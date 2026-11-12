// Testes unitários do JavaScript do tema (workspace.js) em jsdom, com cobertura lcov para o SonarQube.
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

// Fontes do tema ficam fora desta pasta (src/main/resources/theme); o alias evita caminhos relativos frágeis.
const themeDir = fileURLToPath(new URL("../src/main/resources/theme", import.meta.url));

export default defineConfig({
  resolve: { alias: { "@theme": themeDir } },
  server: { fs: { allow: [".."] } },
  test: {
    environment: "jsdom",
    include: ["unit/**/*.test.mjs"],
    coverage: {
      provider: "v8",
      // Os fontes estão fora da raiz do Vitest; o lcov sai com o caminho real (o mesmo /workspace do SonarQube).
      allowExternal: true,
      include: ["**/src/main/resources/theme/**/*.js"],
      // projectRoot "..": caminhos do lcov relativos à raiz do projeto, como o SonarQube espera.
      reporter: ["text", ["lcov", { projectRoot: ".." }]],
      reportsDirectory: "coverage",
      thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 },
    },
  },
});
