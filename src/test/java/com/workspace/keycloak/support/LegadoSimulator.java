package com.workspace.keycloak.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.workspace.keycloak.support.FakeLegadoServer.Received;
import com.workspace.keycloak.support.FakeLegadoServer.Reply;

/**
 * Comportamento do Legado nos testes de integração do reset e da marcação: contas por login/e-mail e as três rotas
 * internas usadas pelo Keycloak (verificação de credenciais, consulta de usuário e marcação de migração).
 * Logins com prefixo {@code falha.} respondem 503. A marcação é registrada em {@link #marks()}.
 */
public final class LegadoSimulator {

    /** Conta simulada: senha, resultado da verificação (valid/locked/migrated) e JSON do usuário. */
    private record Account(String password, String outcome, String userJson) {
    }

    /** Marcação recebida: id do Legado (da rota) e corpo. */
    public record Mark(String legacyUserId, String body) {
    }

    private static final Pattern LOGIN_FIELD = Pattern.compile("\"login\":\"([^\"]*)\"");
    private static final Pattern PASSWORD_FIELD = Pattern.compile("\"password\":\"([^\"]*)\"");
    private static final Pattern MIGRATION_PATH = Pattern.compile("/users/([^/]+)/migration");

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final List<Mark> marks = new CopyOnWriteArrayList<>();
    private volatile int markStatus = 204;

    /** Liga o simulador ao servidor falso (substitui o responder anterior). */
    public void attachTo(FakeLegadoServer server) {
        server.responder(this::reply);
    }

    public void reset() {
        marks.clear();
        markStatus = 204;
    }

    public List<Mark> marks() {
        return marks;
    }

    /** Status da próxima marcação: 204 (padrão), 409 ou 503. */
    public void markStatus(int status) {
        this.markStatus = status;
    }

    /** Conta ativa do Legado (a senha confere em {@code valid}); {@code outcome} pode ser locked ou migrated. */
    public void account(String userName, String email, String password, String outcome, String role) {
        Account account = new Account(password, outcome, userJson(userName, email, role));
        accounts.put(userName, account);
        accounts.put(email, account);
    }

    /** Id estável por usuário (UUID derivado do nome). */
    public static String legacyId(String userName) {
        return UUID.nameUUIDFromBytes(userName.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String userJson(String userName, String email, String role) {
        return """
                {"id":"%s","userName":"%s","email":"%s","emailConfirmed":true,"fullName":"Usuario Legado",
                 "mobilePhone":"11987654321",
                 "address":{"postalCode":"01001-000","street":"Praça da Sé","number":"1","complement":null,
                            "district":"Sé","city":"São Paulo","cityIbgeCode":3550308,"state":"SP"},
                 "roles":["%s"]}""".formatted(legacyId(userName), userName, email, role);
    }

    private Reply reply(Received request) {
        if ("PUT".equals(request.method())) {
            Matcher path = MIGRATION_PATH.matcher(request.pathAndQuery());
            marks.add(new Mark(path.find() ? path.group(1) : "", request.body()));
            return new Reply(markStatus, markStatus == 204 ? "" : "{}");
        }
        String login = group(LOGIN_FIELD, request.body()).strip().toLowerCase(java.util.Locale.ROOT);
        if (login.startsWith("falha.")) {
            return new Reply(503, "{}");
        }
        Account account = accounts.get(login);
        if (request.pathAndQuery().endsWith("user-lookups")) {
            return lookup(account);
        }
        return verify(account, group(PASSWORD_FIELD, request.body()));
    }

    private static Reply lookup(Account account) {
        if (account == null) {
            return new Reply(200, "{\"outcome\":\"not_found\"}");
        }
        return "migrated".equals(account.outcome())
                ? new Reply(200, "{\"outcome\":\"migrated\"}")
                : new Reply(200, "{\"outcome\":\"found\",\"user\":" + account.userJson() + "}");
    }

    private static Reply verify(Account account, String password) {
        if (account == null) {
            return new Reply(200, "{\"outcome\":\"not_found\"}");
        }
        if (!account.password().equals(password)) {
            return new Reply(200, "{\"outcome\":\"invalid_password\"}");
        }
        return "valid".equals(account.outcome())
                ? new Reply(200, "{\"outcome\":\"valid\",\"user\":" + account.userJson() + "}")
                : new Reply(200, "{\"outcome\":\"" + account.outcome() + "\"}");
    }

    private static String group(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }
}
