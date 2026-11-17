package com.workspace.keycloak.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

/**
 * Navegador mínimo para os fluxos de reset de senha: guarda os cookies da sessão de login (o Keycloak os envia com
 * {@code Secure} e o Apache HttpClient não os reenvia para http) e não segue redirecionamentos sozinho.
 */
public final class BrowserFlow {

    /** Página devolvida: status, corpo e Location. */
    public record Page(int status, String body, String location) {
    }

    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form id=\"kc-form-login\" action=\"([^\"]+)\"");
    private static final Pattern RESET_LINK = Pattern.compile("href=\"([^\"]{0,500}/login-actions/reset-credentials[^\"]{0,500})\"");
    private static final Pattern RESET_FORM_ACTION =
            Pattern.compile("<form id=\"kc-reset-password-form\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern UPDATE_FORM_ACTION =
            Pattern.compile("<form id=\"kc-passwd-update-form\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern ACTION_TOKEN_LINK =
            Pattern.compile("(http[^\\s\"<>]*/login-actions/action-token\\?[^\\s\"<>]*)");
    private static final int MAX_HOPS = 4;

    private final HttpClient httpClient;
    private final String realmBaseUrl;
    private final String keycloakBase;
    private final String clientId;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public BrowserFlow(HttpClient httpClient, String realmBaseUrl, String keycloakBase, String clientId) {
        this.httpClient = httpClient;
        this.realmBaseUrl = realmBaseUrl;
        this.keycloakBase = keycloakBase;
        this.clientId = clientId;
    }

    /** Abre a tela de login, segue "Esqueci minha senha" e devolve a tela do reset (campo de login). */
    public Page openResetPage() throws IOException, URISyntaxException {
        return send(new HttpGet(absolute(find(RESET_LINK, openLoginPage()))));
    }

    /** Entra com login e senha e segue os redirecionamentos internos (ex.: tela "Atualizar senha"). */
    public Page login(String username, String password) throws IOException, URISyntaxException {
        Page loginPage = openLoginPage();
        return follow(send(form(find(LOGIN_FORM_ACTION, loginPage), new BasicNameValuePair("username", username),
                new BasicNameValuePair("password", password))));
    }

    private Page openLoginPage() throws IOException, URISyntaxException {
        return send(new HttpGet(new URIBuilder(realmBaseUrl + "/protocol/openid-connect/auth")
                .addParameter("client_id", clientId)
                .addParameter("response_type", "code")
                .addParameter("scope", "openid")
                .addParameter("redirect_uri", redirectUri())
                .addParameter("code_challenge", CODE_CHALLENGE)
                .addParameter("code_challenge_method", "S256").build()));
    }

    /** Pede o reset com o login/e-mail informado e devolve a página de resposta (a mesma em todos os casos). */
    public Page requestReset(String usernameOrEmail) throws IOException, URISyntaxException {
        Page resetPage = openResetPage();
        return send(form(find(RESET_FORM_ACTION, resetPage), new BasicNameValuePair("username", usernameOrEmail)));
    }

    /** Abre o link do e-mail (corpo em quoted-printable) e devolve a tela de nova senha. */
    public Page openEmailLink(String message) throws IOException {
        String decoded = message.replace("=\r\n", "").replace("=3D", "=");
        Matcher matcher = ACTION_TOKEN_LINK.matcher(decoded);
        assertThat(matcher.find()).as("link do e-mail de reset").isTrue();
        return follow(send(new HttpGet(htmlUrl(matcher.group(1)))));
    }

    /** Envia a nova senha na tela "Atualizar senha" e segue até o fim do login. */
    public Page submitNewPassword(Page updatePage, String password) throws IOException {
        return follow(send(form(find(UPDATE_FORM_ACTION, updatePage),
                new BasicNameValuePair("password-new", password),
                new BasicNameValuePair("password-confirm", password))));
    }

    public String redirectUri() {
        return keycloakBase + "/realms/workspace/account/";
    }

    private HttpPost form(String action, NameValuePair... fields) {
        HttpPost post = new HttpPost(htmlUrl(action));
        post.setEntity(new UrlEncodedFormEntity(List.of(fields), StandardCharsets.UTF_8));
        return post;
    }

    private Page follow(Page page) throws IOException {
        Page current = page;
        for (int hop = 0; hop < MAX_HOPS && current.status() == 302 && current.location() != null
                && current.location().contains("/login-actions/"); hop++) {
            current = send(new HttpGet(current.location()));
        }
        return current;
    }

    private Page send(HttpRequestBase request) throws IOException {
        request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
        if (!cookies.isEmpty()) {
            request.setHeader("Cookie", cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("; ")));
        }
        request.setHeader("Accept-Language", "pt-BR");
        HttpResponse response = httpClient.execute(request, HttpClientContext.create());
        for (Header header : response.getHeaders("Set-Cookie")) {
            String[] pair = header.getValue().split(";", 2)[0].split("=", 2);
            cookies.put(pair[0], pair.length > 1 ? pair[1] : "");
        }
        String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
        Header location = response.getFirstHeader("Location");
        return new Page(response.getStatusLine().getStatusCode(), body, location == null ? null : location.getValue());
    }

    private static String find(Pattern pattern, Page page) {
        Matcher matcher = pattern.matcher(page.body());
        assertThat(matcher.find()).as(pattern + " na página").isTrue();
        return matcher.group(1);
    }

    private static String htmlUrl(String href) {
        return href.replace("&amp;", "&");
    }

    private String absolute(String href) {
        String url = htmlUrl(href);
        return url.startsWith("/") ? keycloakBase + url : url;
    }
}
