// Testes E2E do workspace.js (tema de login): estado de envio, aviso de Caps Lock e links de provedor de uso único.
const { test, expect } = require("@playwright/test");

// Code challenge S256 fixo: os testes só abrem a tela de login, nunca trocam o code.
const CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

async function openLoginPage(page, baseURL) {
  const query = new URLSearchParams({
    client_id: "account-console",
    response_type: "code",
    scope: "openid",
    redirect_uri: `${baseURL}/realms/workspace/account/`,
    code_challenge: CODE_CHALLENGE,
    code_challenge_method: "S256",
  });
  await page.goto(`/realms/workspace/protocol/openid-connect/auth?${query}`);
  await expect(page.locator("#kc-login")).toBeVisible();
}

// Conta os envios do formulário e cancela a navegação, para a tela continuar inspecionável depois do submit.
// Registrado depois do workspace.js, este listener roda após o dele: o estado de envio já foi aplicado.
async function captureSubmissions(page) {
  await page.evaluate(() => {
    window.submitCount = 0;
    document.querySelector("#kc-form-login").addEventListener("submit", (event) => {
      window.submitCount += 1;
      event.preventDefault();
    });
  });
}

const submitCount = (page) => page.evaluate(() => window.submitCount);

test.describe("login form submission", () => {
  test("disables the button and announces progress while signing in", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);
    await captureSubmissions(page);
    const button = page.locator("#kc-login");

    await page.fill("#username", "e2e.user");
    await page.fill("#password", "e2e-password");
    await button.click();

    await expect(button).toBeDisabled();
    await expect(button).toHaveAttribute("aria-busy", "true");
    await expect(button).toContainText("Entrando…");
    await expect(button.locator(".spinner-border")).toBeVisible();
    expect(await submitCount(page)).toBe(1);
  });

  test("submits only once when the user submits repeatedly", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);
    await captureSubmissions(page);

    await page.fill("#username", "e2e.user");
    await page.fill("#password", "e2e-password");
    await page.press("#password", "Enter");
    await page.press("#password", "Enter");
    await page.locator("#kc-login").click({ force: true });

    expect(await submitCount(page)).toBe(1);
  });
});

test.describe("caps lock warning", () => {
  test("shows the warning while Caps Lock is on and hides it when turned off", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);
    const password = page.locator("#password");
    const warning = page.locator("#caps-lock-warning");

    await expect(warning).toBeHidden();
    await password.dispatchEvent("keydown", { key: "A", modifierCapsLock: true });
    await expect(warning).toBeVisible();
    await expect(warning).toContainText("O Caps Lock está ativado.");

    await password.dispatchEvent("keyup", { key: "a", modifierCapsLock: false });
    await expect(warning).toBeHidden();
  });

  test("hides the warning when the password field loses focus", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);
    const password = page.locator("#password");
    const warning = page.locator("#caps-lock-warning");

    await password.focus();
    await password.dispatchEvent("keydown", { key: "A", modifierCapsLock: true });
    await expect(warning).toBeVisible();

    await page.locator("#username").focus();
    await expect(warning).toBeHidden();
  });

  test("keeps the password field described by the warning for screen readers", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);

    await expect(page.locator("#password")).toHaveAttribute("aria-describedby", /caps-lock-warning/);
    await expect(page.locator("#caps-lock-warning")).toHaveAttribute("aria-live", "polite");
  });
});

test.describe("identity provider links", () => {
  test("allow a single activation and mark the link as disabled", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);
    // O realm não tem provedores externos: injeta um link com a mesma marcação gerada pelo login.ftl.
    await page.evaluate(() => {
      const link = document.createElement("a");
      link.id = "probe-idp";
      link.href = "#probe-idp";
      link.textContent = "Probe IdP";
      link.dataset.onceLink = "";
      link.dataset.disabledClass = "disabled opacity-50";
      document.querySelector("#kc-content").append(link);
      window.probeClicks = [];
      window.addEventListener("click", (event) => {
        if (event.target.closest("#probe-idp")) {
          window.probeClicks.push(event.defaultPrevented);
        }
      });
    });
    const link = page.locator("#probe-idp");

    await link.evaluate((element) => element.click());
    await link.evaluate((element) => element.click());

    expect(await page.evaluate(() => window.probeClicks)).toEqual([false, true]);
    await expect(link).toHaveAttribute("aria-disabled", "true");
    await expect(link).toHaveClass(/\bdisabled\b/);
    await expect(link).toHaveClass(/\bopacity-50\b/);
  });
});

test("login page loads without console errors", async ({ page, baseURL }) => {
  const errors = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      errors.push(message.text());
    }
  });
  page.on("pageerror", (error) => errors.push(error.message));

  await openLoginPage(page, baseURL);

  expect(errors).toEqual([]);
});

// Contraste de componentes (WCAG 1.4.11) medido no navegador real, contra o fundo efetivo da página.
test.describe("form field contrast", () => {
  const contrastOf = (page, selector) =>
    page.evaluate((sel) => {
      const parse = (c) => c.match(/[\d.]+/g).slice(0, 3).map(Number);
      const lum = (rgb) => {
        const [r, g, b] = rgb.map((v) => {
          const s = v / 255;
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        });
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      };
      const field = document.querySelector(sel);
      const [a, b] = [lum(parse(getComputedStyle(field).borderTopColor)), lum(parse(getComputedStyle(document.body).backgroundColor))];
      return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }, selector);

  // Regressão: a borda dos campos herdava o cinza do Bootstrap (#dee2e6), com 1,22:1 sobre o fundo.
  test("field borders reach 3:1 at rest and when focused", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);
    await page.locator("#username").blur();

    expect(await contrastOf(page, "#password")).toBeGreaterThanOrEqual(3);
    expect(await contrastOf(page, "#username")).toBeGreaterThanOrEqual(3);

    await page.locator("#password").focus();

    expect(await contrastOf(page, "#password")).toBeGreaterThanOrEqual(3);
  });
});

test.describe("accessibility", () => {
  const AxeBuilder = require("@axe-core/playwright").default;

  test("login page has no serious or critical axe violations", async ({ page, baseURL }) => {
    await openLoginPage(page, baseURL);

    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    const blocking = results.violations
      .filter((v) => ["serious", "critical"].includes(v.impact))
      .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);

    expect(blocking).toEqual([]);
  });
});
