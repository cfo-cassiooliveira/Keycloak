package com.workspace.keycloak.support;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

/** Chamadas HTTP aos endpoints OIDC do Keycloak usadas pelos testes de integração. */
public final class OidcHttp {

    // Code challenge S256 fixo: os testes só precisam que o parâmetro seja válido, não trocam o code.
    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form id=\"kc-form-login\" action=\"([^\"]+)\"");

    private OidcHttp() {
    }

    /** Resposta simplificada: status, corpo e header Location. */
    public record Response(int status, String body, String location) {
    }

    public static Response passwordGrant(HttpClient httpClient, String realmBaseUrl, String clientId,
            String username, String password) throws IOException {
        HttpPost post = new HttpPost(realmBaseUrl + "/protocol/openid-connect/token");
        List<NameValuePair> form = List.of(
                new BasicNameValuePair("grant_type", "password"),
                new BasicNameValuePair("client_id", clientId),
                new BasicNameValuePair("username", username),
                new BasicNameValuePair("password", password));
        post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        return execute(httpClient, post);
    }

    /** Pede a tela de login (Authorization Code) sem seguir redirecionamentos. */
    public static Response authorizationRequest(HttpClient httpClient, String realmBaseUrl, String clientId,
            String redirectUri, boolean withPkce, String acceptLanguage) throws IOException {
        return execute(httpClient, authorizationGet(realmBaseUrl, clientId, redirectUri, withPkce, acceptLanguage),
                HttpClientContext.create());
    }

    /**
     * Abre a tela de login e envia o formulário (fluxo real do navegador).
     * Os cookies do Keycloak têm "Secure"; navegadores os enviam para http://localhost, mas o Apache HttpClient não,
     * por isso são repassados manualmente do GET para o POST.
     */
    public static Response submitLoginForm(HttpClient httpClient, String realmBaseUrl, String clientId,
            String redirectUri, String username, String password) throws IOException {
        HttpResponse pageResponse = httpClient.execute(
                authorizationGet(realmBaseUrl, clientId, redirectUri, true, "pt-BR"), HttpClientContext.create());
        String page = EntityUtils.toString(pageResponse.getEntity());
        String cookies = Arrays.stream(pageResponse.getHeaders("Set-Cookie"))
                .map(header -> header.getValue().split(";", 2)[0])
                .collect(Collectors.joining("; "));
        Matcher action = LOGIN_FORM_ACTION.matcher(page);
        if (!action.find()) {
            throw new IllegalStateException("Login form not found on page");
        }
        HttpPost post = new HttpPost(action.group(1).replace("&amp;", "&"));
        post.setHeader("Cookie", cookies);
        post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("username", username),
                new BasicNameValuePair("password", password)), StandardCharsets.UTF_8));
        return execute(httpClient, post, HttpClientContext.create());
    }

    public static int getStatus(HttpClient httpClient, String url) throws IOException {
        return execute(httpClient, new HttpGet(url), HttpClientContext.create()).status();
    }

    private static HttpGet authorizationGet(String realmBaseUrl, String clientId, String redirectUri,
            boolean withPkce, String acceptLanguage) {
        try {
            URIBuilder uri = new URIBuilder(realmBaseUrl + "/protocol/openid-connect/auth")
                    .addParameter("client_id", clientId)
                    .addParameter("response_type", "code")
                    .addParameter("scope", "openid")
                    .addParameter("redirect_uri", redirectUri);
            if (withPkce) {
                uri.addParameter("code_challenge", CODE_CHALLENGE).addParameter("code_challenge_method", "S256");
            }
            HttpGet get = new HttpGet(uri.build());
            get.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            get.setHeader("Accept-Language", acceptLanguage);
            return get;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid realm URL", e);
        }
    }

    private static Response execute(HttpClient httpClient, HttpUriRequest request) throws IOException {
        return execute(httpClient, request, HttpClientContext.create());
    }

    private static Response execute(HttpClient httpClient, HttpUriRequest request, HttpClientContext context)
            throws IOException {
        HttpResponse response = httpClient.execute(request, context);
        String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
        String location = response.getFirstHeader("Location") == null
                ? null
                : response.getFirstHeader("Location").getValue();
        return new Response(response.getStatusLine().getStatusCode(), body, location);
    }
}
