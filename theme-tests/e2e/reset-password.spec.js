// E2E das telas "Esqueci minha senha" e "Atualizar senha" do tema (T16): microtextos da spec 001 do cadastro (§3b),
// estados (vazio, sucesso neutro, foco) e acessibilidade, contra o Keycloak do docker compose.
const { test, expect } = require("@playwright/test");
const AxeBuilder = require("@axe-core/playwright").default;

// Code challenge S256 fixo: os testes só abrem telas, nunca trocam o code.
const CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
const NEUTRAL_MESSAGE = "Se houver uma conta para esse login ou e‑mail, você receberá as instruções em instantes.";
const MISSING_USERNAME_MESSAGE = "Informe seu login ou e-mail.";
const UPDATE_PASSWORD_NOTICE = "Sua senha precisa ser atualizada para a nova política de segurança.";

async function openResetPage(page, baseURL) {
  const query = new URLSearchParams({
    client_id: "account-console",
    response_type: "code",
    scope: "openid",
    redirect_uri: `${baseURL}/realms/workspace/account/`,
    code_challenge: CODE_CHALLENGE,
    code_challenge_method: "S256",
  });
  await page.goto(`/realms/workspace/protocol/openid-connect/auth?${query}`);
  await page.getByRole("link", { name: /esqueci/i }).click();
  await expect(page.locator("#username")).toBeVisible();
}

const blockingViolations = async (page) => {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  return results.violations
    .filter((v) => ["serious", "critical"].includes(v.impact))
    .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);
};

test.describe("forgot password", () => {
  test("asks for the login when it is empty", async ({ page, baseURL }) => {
    await openResetPage(page, baseURL);

    await page.locator("form").evaluate((form) => form.noValidate = true);
    await page.getByRole("button", { name: /enviar|continuar|submit/i }).click();

    await expect(page.getByText(MISSING_USERNAME_MESSAGE)).toBeVisible();
    await expect(page.getByText(NEUTRAL_MESSAGE)).toHaveCount(0);
  });

  test("shows the same neutral message for a login that does not exist", async ({ page, baseURL }) => {
    await openResetPage(page, baseURL);

    await page.fill("#username", "e2e.ninguem@example.com");
    await page.getByRole("button", { name: /enviar|continuar|submit/i }).click();

    await expect(page.getByText(NEUTRAL_MESSAGE)).toBeVisible();
    await expect(page.locator("#kc-login")).toBeVisible();
  });

  test("reset page has no serious or critical axe violations", async ({ page, baseURL }) => {
    await openResetPage(page, baseURL);

    expect(await blockingViolations(page)).toEqual([]);
  });

  test("neutral message page has no serious or critical axe violations", async ({ page, baseURL }) => {
    await openResetPage(page, baseURL);
    await page.fill("#username", "e2e.ninguem@example.com");
    await page.getByRole("button", { name: /enviar|continuar|submit/i }).click();
    await expect(page.getByText(NEUTRAL_MESSAGE)).toBeVisible();

    expect(await blockingViolations(page)).toEqual([]);
  });
});

// A tela "Atualizar senha" só aparece para um usuário com a ação obrigatória; o teste cria um pelo Admin API com o
// token da conta de serviço workspace-automation (client credentials; o admin-cli não tem password grant). O segredo
// vem de KC_AUTOMATION_CLIENT_SECRET (do .env, repassado pelo scripts/e2e.sh); sem ele o teste é ignorado.
// O token é pedido com fetch do Node (e não com o "request" do Playwright) para o segredo não entrar no trace.
test.describe("update password after migration", () => {
  const automationSecret = process.env.KC_AUTOMATION_CLIENT_SECRET;
  test.skip(!automationSecret, "defina KC_AUTOMATION_CLIENT_SECRET (.env do keycloak) para criar o usuário de teste");

  test("shows the update notice and has no serious or critical axe violations", async ({ page, baseURL, request }) => {
    const tokenResponse = await fetch(`${baseURL}/realms/master/protocol/openid-connect/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: "workspace-automation",
        client_secret: automationSecret,
      }),
    });
    expect(tokenResponse.ok).toBeTruthy();
    const token = await tokenResponse.json();
    const headers = { Authorization: `Bearer ${token.access_token}` };
    const username = `e2e.update.${Date.now()}`;
    const created = await request.post("/admin/realms/workspace/users", {
      headers,
      data: {
        username,
        email: `${username}@example.com`,
        enabled: true,
        emailVerified: true,
        firstName: "E2E",
        lastName: "Update",
        requiredActions: ["UPDATE_PASSWORD"],
        credentials: [{ type: "password", value: "Senha-Forte-2026!", temporary: false }],
      },
    });
    expect(created.ok()).toBeTruthy();

    const query = new URLSearchParams({
      client_id: "account-console",
      response_type: "code",
      scope: "openid",
      redirect_uri: `${baseURL}/realms/workspace/account/`,
      code_challenge: CODE_CHALLENGE,
      code_challenge_method: "S256",
    });
    await page.goto(`/realms/workspace/protocol/openid-connect/auth?${query}`);
    await page.fill("#username", username);
    await page.fill("#password", "Senha-Forte-2026!");
    await page.locator("#kc-login").click();

    await expect(page.locator("#kc-passwd-update-form")).toBeVisible();
    await expect(page.getByText(UPDATE_PASSWORD_NOTICE)).toBeVisible();
    expect(await blockingViolations(page)).toEqual([]);
  });
});
