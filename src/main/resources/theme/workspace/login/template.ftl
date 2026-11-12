<#-- Layout de todas as telas de login do Workspace (Bootstrap 5).
     Mantém os comportamentos do template "base" do Keycloak 26.8: polling de sessão, verificação da sessão de
     autenticação, importmap usado por WebAuthn/passkeys, "tentar outro jeito" e troca de organização. -->
<#import "footer.ftl" as loginFooter>
<#import "theme-resources.ftl" as themeResourceTags>
<#-- Sufixo de versão dos recursos do tema (cache-busting; ver workspaceAssetsVersion no theme.properties). -->
<#assign assetVersion = "?v=" + (properties.workspaceAssetsVersion!"dev")>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <meta name="robots" content="noindex, nofollow">
    <meta name="theme-color" content="#262a6b">
    <title>${title!}</title>
    <link rel="icon" href="${url.resourcesPath}/img/logo.svg${assetVersion}" type="image/svg+xml">
    <link rel="preload" href="${url.resourcesPath}/fonts/manrope/manrope-latin-wght-normal.woff2" as="font" type="font/woff2" crossorigin>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}${assetVersion}" rel="stylesheet">
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module" src="${url.resourcesPath}/js/workspace.js${assetVersion}"></script>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <#if authenticationSession??>
        <script type="module">
            <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!} ${bodyClass}" data-page-id="login-${pageId}">
<div class="container-fluid p-0">
    <div class="row g-0 min-vh-100">

        <#-- Painel da marca: só em telas grandes; no celular a marca vai para a barra superior. -->
        <aside class="col-lg-5 col-xl-4 d-none d-lg-flex flex-column ws-brand">
            <div class="d-flex align-items-center gap-2">
                <img src="${url.resourcesPath}/img/logo.svg${assetVersion}" alt="" width="44" height="44">
                <span class="ws-brand-name">${realm.displayName!'Workspace'}</span>
            </div>
            <div class="ws-mosaic my-auto" aria-hidden="true">
                <#list 1..30 as tile>
                    <span class="ws-tile ws-tile-${tile % 4}<#if tile == 17> ws-tile-you</#if>"></span>
                </#list>
            </div>
            <div>
                <p class="ws-brand-headline">${msg("workspaceBrandHeadline")}</p>
                <p class="ws-brand-body mb-0">${msg("workspaceBrandBody")}</p>
            </div>
        </aside>

        <main class="col-lg-7 col-xl-8 d-flex flex-column ws-main">
            <header class="d-flex align-items-center justify-content-between gap-3 px-3 px-sm-4 py-3">
                <div class="d-flex d-lg-none align-items-center gap-2">
                    <img src="${url.resourcesPath}/img/logo.svg${assetVersion}" alt="" width="32" height="32">
                    <span class="ws-brand-name ws-brand-name-compact">${realm.displayName!'Workspace'}</span>
                </div>
                <#if realm.internationalizationEnabled && locale.supported?size gt 1>
                    <nav class="ms-auto" aria-label="${msg("languages")}">
                        <ul class="nav flex-nowrap ws-locale">
                            <#list locale.supported as l>
                                <li class="nav-item">
                                    <a class="nav-link" href="${l.url}" lang="${l.languageTag}" aria-label="${l.label}"
                                       <#if l.languageTag == locale.currentLanguageTag>aria-current="true"</#if>><span class="d-none d-sm-inline">${l.label}</span><span class="d-sm-none">${l.languageTag?upper_case}</span></a>
                                </li>
                            </#list>
                        </ul>
                    </nav>
                </#if>
            </header>

            <div class="flex-grow-1 d-flex align-items-start align-items-md-center justify-content-center px-3 px-sm-4 pt-4 pt-md-0 pb-4">
                <div class="ws-panel w-100" id="kc-content">

                    <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
                        <h1 id="kc-page-title" class="ws-title"><#nested "header"></h1>
                    <#else>
                        <#nested "show-username">
                        <div id="kc-username" class="ws-identity d-flex align-items-center justify-content-between gap-2 mb-4">
                            <span id="kc-attempted-username" class="text-truncate fw-semibold">${auth.attemptedUsername}</span>
                            <a id="reset-login" class="btn btn-sm btn-link text-decoration-none flex-shrink-0"
                               href="${url.loginRestartFlowUrl}">${msg("restartLoginTooltip")}</a>
                        </div>
                    </#if>

                    <#if displayRequiredFields>
                        <p class="small text-body-secondary"><span class="text-danger" aria-hidden="true">*</span> ${msg("requiredFields")}</p>
                    </#if>

                    <#-- Ações iniciadas pela aplicação não exibem o aviso de ação pendente durante o login. -->
                    <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                        <#assign alertType = (message.type == 'error')?then('danger', message.type)>
                        <div class="${properties.kcAlertClass!} alert-${alertType}" role="<#if message.type == 'error'>alert<#else>status</#if>">
                            <#if message.type = 'success'><span class="${properties.kcFeedbackSuccessIcon!}" aria-hidden="true"></span></#if>
                            <#if message.type = 'warning'><span class="${properties.kcFeedbackWarningIcon!}" aria-hidden="true"></span></#if>
                            <#if message.type = 'error'><span class="${properties.kcFeedbackErrorIcon!}" aria-hidden="true"></span></#if>
                            <#if message.type = 'info'><span class="${properties.kcFeedbackInfoIcon!}" aria-hidden="true"></span></#if>
                            <span>${kcSanitize(message.summary)?no_esc}</span>
                        </div>
                    </#if>

                    <#nested "form">

                    <#if auth?has_content && auth.showTryAnotherWayLink()>
                        <form id="kc-select-try-another-way-form" class="mt-3 text-center" action="${url.loginAction}" method="post">
                            <input type="hidden" name="tryAnotherWay" value="on"/>
                            <button type="submit" id="try-another-way" class="btn btn-link">${msg("doTryAnotherWay")}</button>
                        </form>
                    </#if>

                    <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
                        <form id="kc-switch-organization-form" class="mt-3 text-center" action="${url.loginAction}" method="post">
                            <input type="hidden" name="switchOrganization" value="true"/>
                            <button type="submit" id="switch-organization" class="btn btn-link">${msg("doSwitchOrganization")}</button>
                        </form>
                    </#if>

                    <#nested "socialProviders">

                    <#if displayInfo>
                        <div id="kc-info" class="${properties.kcSignUpClass!}">
                            <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                                <#nested "info">
                            </div>
                        </div>
                    </#if>

                    <@loginFooter.content/>
                </div>
            </div>

            <footer class="px-3 px-sm-4 py-3 small text-body-secondary ws-footer">
                ${msg("workspaceHelp")}
            </footer>
        </main>
    </div>
</div>
</body>
</html>
</#macro>
