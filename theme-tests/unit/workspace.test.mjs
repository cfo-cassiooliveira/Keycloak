// Testes unitários do workspace.js (tema de login): estado de envio, aviso de Caps Lock e links de uso único.
// O módulo registra os comportamentos ao ser importado; cada teste monta o DOM e importa uma instância nova.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const MODULE_PATH = "@theme/workspace/login/resources/js/workspace.js";

async function loadWorkspaceScript() {
  vi.resetModules();
  await import(MODULE_PATH);
}

function renderLoginForm({ withLoadingLabel = true, withSpinner = true, withLabel = true } = {}) {
  document.body.innerHTML = `
    <form id="kc-form-login" data-ws-submit>
      <input id="username">
      <input id="password" type="password">
      <div id="caps-lock-warning" hidden>Caps Lock</div>
      <button id="kc-login" type="submit" ${withLoadingLabel ? 'data-loading-label="Entrando…"' : ""}>
        ${withSpinner ? '<span class="spinner-border" hidden></span>' : ""}
        ${withLabel ? "<span data-label>Entrar</span>" : ""}
      </button>
    </form>`;
}

function submit(form) {
  // jsdom não navega: o evento submit é suficiente para o comportamento testado.
  form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
}

function keyEvent(type, capsLock) {
  const event = new KeyboardEvent(type, { key: "A" });
  event.getModifierState = (modifier) => modifier === "CapsLock" && capsLock;
  return event;
}

let clickListeners;
const originalAddEventListener = document.addEventListener.bind(document);

beforeEach(() => {
  // Os listeners de clique do módulo ficam no document; guardamos para removê-los entre os testes.
  clickListeners = [];
  vi.spyOn(document, "addEventListener").mockImplementation((type, listener, options) => {
    if (type === "click") {
      clickListeners.push(listener);
    }
    return originalAddEventListener(type, listener, options);
  });
});

afterEach(() => {
  clickListeners.forEach((listener) => document.removeEventListener("click", listener));
  vi.restoreAllMocks();
  document.body.innerHTML = "";
});

describe("login form submission", () => {
  it("disables the button, shows the spinner and the loading label", async () => {
    renderLoginForm();
    await loadWorkspaceScript();
    const button = document.getElementById("kc-login");

    submit(document.getElementById("kc-form-login"));

    expect(button.disabled).toBe(true);
    expect(button.getAttribute("aria-busy")).toBe("true");
    expect(button.querySelector(".spinner-border").hasAttribute("hidden")).toBe(false);
    expect(button.querySelector("[data-label]").textContent).toBe("Entrando…");
  });

  it("works when the button has no spinner or label", async () => {
    renderLoginForm({ withSpinner: false, withLabel: false });
    await loadWorkspaceScript();

    submit(document.getElementById("kc-form-login"));

    expect(document.getElementById("kc-login").disabled).toBe(true);
  });

  it("does nothing when the submit button has no loading label", async () => {
    renderLoginForm({ withLoadingLabel: false });
    await loadWorkspaceScript();

    submit(document.getElementById("kc-form-login"));

    expect(document.getElementById("kc-login").disabled).toBe(false);
  });
});

describe("caps lock warning", () => {
  it("shows the warning while Caps Lock is on and hides it when off", async () => {
    renderLoginForm();
    await loadWorkspaceScript();
    const password = document.getElementById("password");
    const warning = document.getElementById("caps-lock-warning");

    password.dispatchEvent(keyEvent("keydown", true));
    expect(warning.hidden).toBe(false);

    password.dispatchEvent(keyEvent("keyup", false));
    expect(warning.hidden).toBe(true);
  });

  it("hides the warning when the password field loses focus", async () => {
    renderLoginForm();
    await loadWorkspaceScript();
    const password = document.getElementById("password");
    const warning = document.getElementById("caps-lock-warning");
    password.dispatchEvent(keyEvent("keydown", true));

    password.dispatchEvent(new FocusEvent("blur"));

    expect(warning.hidden).toBe(true);
  });

  it("ignores events without modifier state", async () => {
    renderLoginForm();
    await loadWorkspaceScript();
    const password = document.getElementById("password");
    const warning = document.getElementById("caps-lock-warning");
    const event = new Event("keydown");

    password.dispatchEvent(event);

    expect(warning.hidden).toBe(true);
  });

  it("does nothing on pages without a password field", async () => {
    document.body.innerHTML = '<div id="caps-lock-warning" hidden></div>';

    await expect(loadWorkspaceScript()).resolves.toBeUndefined();
  });
});

describe("identity provider links", () => {
  function renderProviderLink(disabledClass) {
    document.body.innerHTML = `<a id="idp" href="#idp" data-once-link ${
      disabledClass === undefined ? "" : `data-disabled-class="${disabledClass}"`
    }><span id="idp-label">Provider</span></a><a id="other" href="#other">Other</a>`;
  }

  function click(element) {
    const event = new MouseEvent("click", { bubbles: true, cancelable: true });
    element.dispatchEvent(event);
    return event;
  }

  it("allows the first activation and blocks the next ones", async () => {
    renderProviderLink("disabled opacity-50");
    await loadWorkspaceScript();
    const link = document.getElementById("idp");

    const first = click(document.getElementById("idp-label"));
    const second = click(link);

    expect(first.defaultPrevented).toBe(false);
    expect(second.defaultPrevented).toBe(true);
    expect(link.getAttribute("aria-disabled")).toBe("true");
    expect(link.classList.contains("disabled")).toBe(true);
    expect(link.classList.contains("opacity-50")).toBe(true);
  });

  it("marks the link as disabled even without a disabled class", async () => {
    renderProviderLink();
    await loadWorkspaceScript();
    const link = document.getElementById("idp");

    click(link);

    expect(link.getAttribute("aria-disabled")).toBe("true");
    expect(link.className).toBe("");
  });

  it("ignores clicks outside identity provider links", async () => {
    renderProviderLink("disabled");
    await loadWorkspaceScript();

    const event = click(document.getElementById("other"));

    expect(event.defaultPrevented).toBe(false);
    expect(document.getElementById("other").hasAttribute("aria-disabled")).toBe(false);
  });
});
