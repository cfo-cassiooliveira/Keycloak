package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LegacyUserMapperTest {

    private static final UUID ID = UUID.fromString("0b2f4c1e-8a3d-4e5f-9a6b-7c8d9e0f1a2b");

    private static LegacyUser user(String fullName, String phone, LegacyAddress address, List<String> roles) {
        return new LegacyUser(ID, "Ana.Souza", " Ana.Souza@Gmail.com ", true, fullName, phone, address, roles);
    }

    private static LegacyAddress address() {
        return new LegacyAddress("01310-100", "Av. Paulista", "1000", null, "Bela Vista", "São Paulo", 3550308, "sp");
    }

    @Test
    void toAttributes_MapsEveryFieldToTheUserProfileFormat() {
        var attributes = LegacyUserMapper.toAttributes(
                user("Ana Souza Lima", "(11) 91234-5678", address(), List.of("User")));

        assertThat(attributes).containsEntry("username", List.of("ana.souza"))
                .containsEntry("email", List.of("ana.souza@gmail.com"))
                .containsEntry("firstName", List.of("Ana"))
                .containsEntry("lastName", List.of("Souza Lima"))
                .containsEntry("mobilePhone", List.of("+5511912345678"))
                .containsEntry("postalCode", List.of("01310100"))
                .containsEntry("street", List.of("Av. Paulista"))
                .containsEntry("addressNumber", List.of("1000"))
                .containsEntry("district", List.of("Bela Vista"))
                .containsEntry("city", List.of("São Paulo"))
                .containsEntry("cityIbgeCode", List.of("3550308"))
                .containsEntry("state", List.of("SP"))
                .containsEntry("legacyUserId", List.of(ID.toString()))
                .doesNotContainKey("complement");
    }

    @Test
    void toAttributes_WhenSingleWordNameAndNoAddress_OmitsLastNameAndAddress() {
        var attributes = LegacyUserMapper.toAttributes(user("Madonna", "+5511912345678", null, List.of()));

        assertThat(attributes).containsEntry("firstName", List.of("Madonna")).doesNotContainKeys("lastName", "street",
                "postalCode").containsEntry("mobilePhone", List.of("+5511912345678"));
    }

    @Test
    void toAttributes_WhenPhoneHasCountryCodeWithoutPlus_AddsPlus() {
        assertThat(LegacyUserMapper.toAttributes(user("Ana", "5511912345678", null, List.of())))
                .containsEntry("mobilePhone", List.of("+5511912345678"));
    }

    @Test
    void toAttributes_WhenPhoneIsUnrecognizable_KeepsRawSoTheUserProfileRejectsIt() {
        assertThat(LegacyUserMapper.toAttributes(user("Ana", "12345", null, List.of())))
                .containsEntry("mobilePhone", List.of("12345"));
    }

    @Test
    void toRealmRoles_WhenAdmin_GrantsAdminAndUser() {
        assertThat(LegacyUserMapper.toRealmRoles(user("Ana", null, null, List.of("Admin"))))
                .containsExactlyInAnyOrder("admin", "user");
    }

    @Test
    void toRealmRoles_WhenUserOrUnknownOrEmpty_GrantsOnlyUser() {
        assertThat(LegacyUserMapper.toRealmRoles(user("Ana", null, null, List.of("User"))))
                .containsExactly("user");
        assertThat(LegacyUserMapper.toRealmRoles(user("Ana", null, null, List.of("Other")))).containsExactly("user");
        assertThat(LegacyUserMapper.toRealmRoles(user("Ana", null, null, List.of()))).containsExactly("user");
    }

    @Test
    void normalizeLogin_TrimsAndLowercases() {
        assertThat(LegacyUserMapper.normalizeLogin("  Ana@Gmail.COM ")).isEqualTo("ana@gmail.com");
        assertThat(LegacyUserMapper.normalizeLogin(null)).isEmpty();
    }
}
