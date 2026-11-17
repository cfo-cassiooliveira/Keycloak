package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MaskedIdentifierTest {

    @ParameterizedTest
    @CsvSource({
            "ana.souza@gmail.com, ana***@gmail",
            "bruno.lima, bru***",
            "al@x.com.br, a***@x",
            "ana@x.com, a***@x",
            "ANA.Souza@Gmail.COM, ana***@gmail",
            "'  Bruno.Lima  ', bru***",
            "joao, joa***",
            "abc, a***",
            "ab, a***",
            "a, a***",
            "abcd, abc***",
            "maria@intranet, mar***@intranet"
    })
    void mask_WhenIdentifierIsValid_FollowsRn10(String input, String expected) {
        assertThat(MaskedIdentifier.mask(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   \t "})
    void mask_WhenBlankOrNull_ReturnsMaskOnly(String input) {
        assertThat(MaskedIdentifier.mask(input)).isEqualTo("***");
    }

    @ParameterizedTest
    @CsvSource({"ana@, ana***", "@gmail.com, ***@gmail", "ana@.com, ana***"})
    void mask_WhenEmailIsMalformed_NeverRevealsMoreThanThreeCharacters(String input, String expected) {
        assertThat(MaskedIdentifier.mask(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana.souza@gmail.com", "bruno.lima", "al@x.com.br"})
    void mask_Always_NeverContainsTheFullValue(String input) {
        assertThat(MaskedIdentifier.mask(input)).doesNotContain(input).contains("***");
    }
}
