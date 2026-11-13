// Configuração dos testes E2E do tema de login. Roda contra o Keycloak do docker compose.
const { defineConfig, devices } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./e2e",
  globalSetup: require.resolve("./global-setup.js"),
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  reporter: [["list"]],
  use: {
    // O Keycloak redireciona sempre para o hostname configurado (KC_HOSTNAME=http://localhost:8180).
    baseURL: process.env.E2E_BASE_URL || "http://localhost:8180",
    locale: "pt-BR",
    trace: "retain-on-failure",
  },
  // Desktop, tablet e celular (frontend.md): a tela de login precisa funcionar nas três classes de dispositivo.
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "tablet", use: { ...devices["iPad (gen 7)"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
});
