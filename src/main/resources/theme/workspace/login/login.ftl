<#-- Tela de login (usuário e senha) do Workspace em Bootstrap 5.
     Mesma lógica do login.ftl do tema "base", com UX revisada: rótulos visíveis, erro junto ao campo,
     sem tabindex positivo, aviso de Caps Lock e botão com estado de envio. -->
<#import "template.ftl" as layout>
<#import "passkeys.ftl" as passkeys>
<#assign hasCredentialError = messagesPerField.existsError('username','password')>
<@layout.registrationLayout displayMessage=!hasCredentialError displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <div id="kc-form">
            <#if realm.password>
                <form id="kc-form-login" action="${url.loginAction}" method="post" novalidate data-ws-submit>
                    <#if !usernameHidden??>
                        <div class="mb-3">
                            <label for="username" class="form-label"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>
                            <input id="username" name="username" type="text" value="${(login.username!'')}"
                                   class="form-control form-control-lg<#if hasCredentialError> is-invalid</#if>"
                                   autofocus autocapitalize="none" spellcheck="false" dir="ltr"
                                   autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}"
                                   <#if hasCredentialError>aria-invalid="true" aria-describedby="input-error"</#if>/>
                        </div>
                    </#if>

                    <div class="mb-3">
                        <div class="d-flex justify-content-between align-items-baseline">
                            <label for="password" class="form-label">${msg("password")}</label>
                            <#if realm.resetPasswordAllowed>
                                <a class="small" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                            </#if>
                        </div>
                        <div class="input-group input-group-lg has-validation" dir="ltr">
                            <input id="password" name="password" type="password" autocomplete="current-password"
                                   class="form-control<#if hasCredentialError> is-invalid</#if>"
                                   aria-describedby="caps-lock-warning<#if hasCredentialError> input-error</#if>"
                                   <#if hasCredentialError>aria-invalid="true"</#if>/>
                            <button class="${properties.kcFormPasswordVisibilityButtonClass!}" type="button"
                                    aria-label="${msg('showPassword')}" aria-controls="password" data-password-toggle
                                    data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                    data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                                <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                            </button>
                        </div>
                        <div id="caps-lock-warning" class="form-text ws-caps-lock" aria-live="polite" hidden>
                            <span class="ws-icon ws-icon-warning" aria-hidden="true"></span> ${msg("workspaceCapsLockOn")}
                        </div>
                        <#if hasCredentialError>
                            <div id="input-error" class="invalid-feedback d-block" aria-live="polite">
                                ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                            </div>
                        </#if>
                    </div>

                    <#if realm.rememberMe && !usernameHidden??>
                        <div class="form-check mb-3">
                            <input class="form-check-input" id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
                            <label class="form-check-label" for="rememberMe">${msg("rememberMe")}</label>
                        </div>
                    </#if>

                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <button class="btn btn-primary btn-lg w-100 mt-2" name="login" id="kc-login" type="submit"
                            data-loading-label="${msg("workspaceSigningIn")}">
                        <span class="spinner-border spinner-border-sm me-2" aria-hidden="true" hidden></span>
                        <span data-label>${msg("doLogIn")}</span>
                    </button>
                </form>
            </#if>
        </div>
        <@passkeys.conditionalUIData />
        <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration">
                ${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a>
            </div>
        </#if>
    <#elseif section = "socialProviders">
        <#if realm.password && social?? && social.providers?has_content>
            <div id="kc-social-providers" class="${properties.kcFormSocialAccountSectionClass!}">
                <div class="ws-divider small text-body-secondary my-3"><span>${msg("identity-provider-login-label")}</span></div>
                <ul class="${properties.kcFormSocialAccountListClass!}">
                    <#list social.providers as p>
                        <li>
                            <a data-once-link data-disabled-class="${properties.kcFormSocialAccountListButtonDisabledClass!}"
                               id="social-${p.alias}" class="${properties.kcFormSocialAccountListButtonClass!}" href="${p.loginUrl}">
                                <#if p.iconClasses?has_content><i class="${p.iconClasses!}" aria-hidden="true"></i></#if>
                                <span>${p.displayName!}</span>
                            </a>
                        </li>
                    </#list>
                </ul>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
