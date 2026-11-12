package com.workspace.keycloak.audit;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.Version;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import com.workspace.keycloak.audit.gelf.GraylogAuditSink;
import com.workspace.keycloak.audit.gelf.GraylogSettings;
import com.workspace.keycloak.audit.gelf.ServiceInfo;

/**
 * Registra o listener {@value #PROVIDER_ID} no Event Listener SPI.
 *
 * <p>Com {@code graylog-url} configurado, a auditoria vai ao Graylog (GELF HTTP) com fallback em arquivo local;
 * sem ela, vai ao console do Keycloak (ADR-0004).</p>
 */
public final class WorkspaceAuditEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "workspace-audit";

    static final String SERVICE_NAME = "keycloak";
    static final String OPTION_GRAYLOG_URL = "graylog-url";
    static final String OPTION_TIMEOUT_MS = "graylog-timeout-ms";
    static final String OPTION_QUEUE_CAPACITY = "graylog-queue-capacity";
    static final String OPTION_FALLBACK_FILE = "fallback-file";
    static final String OPTION_ENVIRONMENT = "environment";

    static final int DEFAULT_TIMEOUT_MS = 2000;
    static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    static final String DEFAULT_FALLBACK_FILE = "/opt/keycloak/data/log/audit-fallback.log";
    static final String DEFAULT_ENVIRONMENT = "local";

    private AuditSink sink;
    private WorkspaceAuditEventListenerProvider provider;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return provider;
    }

    @Override
    public void init(Config.Scope config) {
        Logger auditLogger = Logger.getLogger(ConsoleAuditSink.CATEGORY);
        sink = graylogSettings(config)
                .<AuditSink>map(settings -> GraylogAuditSink.create(settings, serviceInfo(config), auditLogger))
                .orElseGet(() -> new ConsoleAuditSink(auditLogger));
        // O provider não guarda estado, então uma única instância atende todas as sessões.
        provider = new WorkspaceAuditEventListenerProvider(new AuditEventMapper(), sink,
                Logger.getLogger(WorkspaceAuditEventListenerProvider.class));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Sem dependências de outros providers.
    }

    @Override
    public void close() {
        // Entrega ou salva no fallback o que ainda estiver na fila antes do Keycloak desligar.
        if (sink != null) {
            sink.close();
        }
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property().name(OPTION_GRAYLOG_URL).type(ProviderConfigProperty.STRING_TYPE)
                .helpText("GELF HTTP input URL (e.g. http://graylog:12202/gelf). Empty: audit goes to the console.")
                .add()
                .property().name(OPTION_TIMEOUT_MS).type(ProviderConfigProperty.INTEGER_TYPE)
                .defaultValue(DEFAULT_TIMEOUT_MS).helpText("Connect and response timeout per message, in ms.").add()
                .property().name(OPTION_QUEUE_CAPACITY).type(ProviderConfigProperty.INTEGER_TYPE)
                .defaultValue(DEFAULT_QUEUE_CAPACITY)
                .helpText("Pending messages before new ones go straight to the fallback file.").add()
                .property().name(OPTION_FALLBACK_FILE).type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(DEFAULT_FALLBACK_FILE).helpText("File that keeps audit logs Graylog did not accept.")
                .add()
                .property().name(OPTION_ENVIRONMENT).type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(DEFAULT_ENVIRONMENT).helpText("Environment name sent with every log.").add()
                .build();
    }

    static Optional<GraylogSettings> graylogSettings(Config.Scope config) {
        String url = config == null ? null : config.get(OPTION_GRAYLOG_URL);
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GraylogSettings(URI.create(url.trim()),
                Duration.ofMillis(config.getInt(OPTION_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)),
                config.getInt(OPTION_QUEUE_CAPACITY, DEFAULT_QUEUE_CAPACITY),
                Path.of(config.get(OPTION_FALLBACK_FILE, DEFAULT_FALLBACK_FILE))));
    }

    static ServiceInfo serviceInfo(Config.Scope config) {
        String version = Optional.ofNullable(WorkspaceAuditEventListenerProviderFactory.class.getPackage())
                .map(Package::getImplementationVersion)
                .orElse("dev");
        return new ServiceInfo(SERVICE_NAME, version, String.valueOf(Version.VERSION),
                config.get(OPTION_ENVIRONMENT, DEFAULT_ENVIRONMENT), hostName());
    }

    private static String hostName() {
        String hostName = System.getenv("HOSTNAME");
        if (hostName != null && !hostName.isBlank()) {
            return hostName;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
