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
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
