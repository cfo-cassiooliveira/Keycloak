// Comportamentos de UX das telas de login do Workspace. Módulo ES: roda depois do parse do HTML.

// Evita envio duplicado e mostra que o login está em andamento.
for (const form of document.querySelectorAll("form[data-ws-submit]")) {
    form.addEventListener("submit", () => {
        const button = form.querySelector("button[type=submit][data-loading-label]");
        if (!button) {
            return;
        }
        button.disabled = true;
        button.setAttribute("aria-busy", "true");
        button.querySelector(".spinner-border")?.removeAttribute("hidden");
        const label = button.querySelector("[data-label]");
        if (label) {
            label.textContent = button.dataset.loadingLabel;
        }
    });
}

// Avisa quando o Caps Lock está ligado enquanto a senha é digitada.
const password = document.getElementById("password");
const capsLockWarning = document.getElementById("caps-lock-warning");
if (password && capsLockWarning) {
    const updateWarning = (event) => {
        if (typeof event.getModifierState === "function") {
            capsLockWarning.hidden = !event.getModifierState("CapsLock");
        }
    };
    password.addEventListener("keydown", updateWarning);
    password.addEventListener("keyup", updateWarning);
    password.addEventListener("blur", () => {
        capsLockWarning.hidden = true;
    });
}

// Links de provedores externos só podem ser acionados uma vez (mesmo comportamento do template base).
document.addEventListener("click", (event) => {
    const link = event.target.closest("a[data-once-link]");
    if (!link) {
        return;
    }
    if (link.getAttribute("aria-disabled") === "true") {
        event.preventDefault();
        return;
    }
    const { disabledClass } = link.dataset;
    if (disabledClass) {
        link.classList.add(...disabledClass.trim().split(/\s+/));
    }
    link.setAttribute("aria-disabled", "true");
});
