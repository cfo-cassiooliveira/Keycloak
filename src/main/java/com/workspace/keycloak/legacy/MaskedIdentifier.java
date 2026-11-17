package com.workspace.keycloak.legacy;

import java.util.Locale;

/**
 * Máscara de identificador (login ou e-mail) para os eventos de migração (RN-10, ADR-0007).
 *
 * <p>Normaliza (trim, minúsculas) e mantém até 3 primeiros caracteres (1 se o trecho tiver até 3) seguidos de
 * {@code ***}. Em e-mail, acrescenta {@code @} e o primeiro rótulo do domínio. Valor nulo ou vazio vira
 * {@code ***}. Ex.: {@code ana.souza@gmail.com} vira {@code ana***@gmail}.</p>
 */
public final class MaskedIdentifier {

    /** Marca da parte ocultada. */
    public static final String PLACEHOLDER = "***";

    private static final int MAX_VISIBLE = 3;

    private MaskedIdentifier() {
    }

    public static String mask(String identifier) {
        if (identifier == null) {
            return PLACEHOLDER;
        }
        String normalized = identifier.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return PLACEHOLDER;
        }
        int at = normalized.lastIndexOf('@');
        if (at >= 0 && at < normalized.length() - 1) {
            String domainLabel = firstLabel(normalized.substring(at + 1));
            if (!domainLabel.isEmpty()) {
                return maskPart(normalized.substring(0, at)) + "@" + domainLabel;
            }
        }
        return maskPart(normalized);
    }

    // Trecho com até 3 caracteres mostra só o primeiro: mostrar tudo revelaria o valor inteiro.
    private static String maskPart(String part) {
        int length = part.codePointCount(0, part.length());
        int visible = length <= MAX_VISIBLE ? Math.min(1, length) : MAX_VISIBLE;
        return part.substring(0, part.offsetByCodePoints(0, visible)) + PLACEHOLDER;
    }

    private static String firstLabel(String domain) {
        int dot = domain.indexOf('.');
        return dot < 0 ? domain : domain.substring(0, dot);
    }
}
