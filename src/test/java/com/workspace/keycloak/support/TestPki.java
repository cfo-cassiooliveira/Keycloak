package com.workspace.keycloak.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * PKI de teste gerada em runtime com o {@code keytool} do JDK: uma CA e um certificado de servidor
 * ({@code localhost}) assinado por ela. Nada é versionado; as chaves vivem só no diretório temporário do teste.
 */
public final class TestPki {

    private final Path caPem;
    private final KeyStore serverKeyStore;
    private final char[] password;

    private TestPki(Path caPem, KeyStore serverKeyStore, char[] password) {
        this.caPem = caPem;
        this.serverKeyStore = serverKeyStore;
        this.password = password;
    }

    /** Cria CA e certificado de servidor em {@code dir}; {@code name} distingue várias CAs no mesmo diretório. */
    public static TestPki create(Path dir, String name) throws IOException, InterruptedException,
            GeneralSecurityException {
        String secret = UUID.randomUUID().toString();
        Path caStore = dir.resolve(name + "-ca.p12");
        Path serverStore = dir.resolve(name + "-server.p12");
        Path caPem = dir.resolve(name + "-ca.pem");
        Path csr = dir.resolve(name + "-server.csr");
        Path signed = dir.resolve(name + "-server.pem");
        String san = "san=dns:localhost,ip:127.0.0.1";

        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name + " CA",
                "-ext", "bc:c", "-ext", "ku:c=keyCertSign", "-validity", "2", "-keystore", caStore.toString(),
                "-storetype", "PKCS12", "-storepass", secret);
        keytool("-exportcert", "-alias", "ca", "-rfc", "-file", caPem.toString(), "-keystore", caStore.toString(),
                "-storepass", secret);
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", san, "-validity", "2", "-keystore", serverStore.toString(), "-storetype", "PKCS12",
                "-storepass", secret);
        keytool("-certreq", "-alias", "server", "-file", csr.toString(), "-keystore", serverStore.toString(),
                "-storepass", secret);
        keytool("-gencert", "-alias", "ca", "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc",
                "-ext", san, "-ext", "eku=serverAuth", "-validity", "2", "-keystore", caStore.toString(),
                "-storepass", secret);
        keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-noprompt", "-keystore",
                serverStore.toString(), "-storepass", secret);
        keytool("-importcert", "-alias", "server", "-file", signed.toString(), "-noprompt", "-keystore",
                serverStore.toString(), "-storepass", secret);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverStore)) {
            keyStore.load(in, secret.toCharArray());
        }
        return new TestPki(caPem, keyStore, secret.toCharArray());
    }

    /** Arquivo PEM com a CA (o que o cliente recebe em {@code ca-file}). */
    public Path caPem() {
        return caPem;
    }

    /** Contexto TLS do servidor de teste, com o certificado assinado por esta CA. */
    public SSLContext serverContext() throws GeneralSecurityException {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(serverKeyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        String executable = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new java.util.ArrayList<>();
        command.add(executable);
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            // A saída do keytool nunca contém a senha (só o comando falho); o comando em si não é impresso.
            throw new IllegalStateException("keytool failed: " + output);
        }
    }
}
