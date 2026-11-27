# ADR-0009: Login social só com Google, apenas para contas existentes

- **Status**: Aceito (2026-11-26)
- **Data**: 2026-11-26
- **Decisores**: responsável técnico
- **Artigos da Constituição relacionados**: Art. IV.1 (LGPD), Art. IV.2, Art. IV.3 (negar por padrão), Art. IV.4
- **Relacionados**: [ADR-0006](0006-migracao-sob-demanda-do-legado.md), [ADR-0008](0008-claim-origem-da-conta.md)

## Contexto

O responsável decidiu oferecer login social só com Google no realm `workspace`. As contas nascem pelo Cadastro, pela
importação em lote ou pela migração do Legado (por Authenticator no login por senha, não por User Storage SPI), e os
perfis privilegiados dependem de OTP.

## Decisão

1. **Não cria conta.** Primeiro login (`workspace-first-broker-login`): `idp-detect-existing-broker-user` e
   `idp-username-password-form`, ambos obrigatórios. O vínculo só nasce após a senha da conta; não há vínculo
   automático por e-mail. Sem conta (inclusive quem só existe no Legado), o acesso é negado.
2. **Conta sem senha não vincula** (importada ainda não ativada, ou criada pelo reset do Legado): a ativação ou o reset
   continuam obrigatórios, e com eles a conclusão da migração.
3. **E-mail verificado pelo Google**: claim essencial `email_verified=true` no ID token (`disableUserInfo=true`, para
   e-mail e verificação virem do mesmo token assinado).
4. **`trustEmail=false`**: o vínculo é provado pela senha, não pelo e-mail; o Google não altera o `emailVerified`.
5. **OTP depois do Google** (`workspace-post-broker-login`, em todo login pelo Google): OTP para `admin`, `log-investigator`,
   `user-importer` (sem OTP, configura antes de concluir) e para quem já o configurou. Sem condição de LoA: o token sai
   com `acr=password` (padrão do Keycloak para autenticação nova), nunca `otp`; o Cadastro continua pedindo o step-up
   (`acr=otp` + `auth_time`) pelo `workspace-browser`.
   O fluxo termina num `allow-access-authenticator`: só com subfluxos condicionais, o Keycloak nega quem não cai em
   nenhum deles.
6. **Mínimo**: escopos `openid email`, PKCE S256, `storeToken=false`, sem `read-token`, `syncMode=IMPORT` e nenhum
   mapper (não afeta `importLineId`/`legacyUserId`, logo nem `account_origin`).
7. **Sem vínculo pela sessão aberta**: a required action `idp_link` (`kc_action=idp_link:google`) fica desligada no
   realm, porque na 26.8.2 ela não pede a senha de novo. Os demais caminhos já vêm fechados no padrão e são provados por
   teste: o endpoint legado `/broker/google/link` e o `GET /account/linked-accounts/google` dependem de
   `allow-client-initiated-account-linking`, que não é ligado. A role `manage-account-links` (parte de
   `manage-account`) continua, sem caminho que a use para vincular. Desvincular pelo Account Console segue disponível.
8. **Como código**: fluxos no `realm-workspace.json` e no `provision.sh`; provedor em
   `provisioning/workspace-google-idp.json`, criado só pelo provisioner quando `GOOGLE_CLIENT_ID` e
   `GOOGLE_CLIENT_SECRET` estão definidos (`.env`).
   Sem as variáveis, um provedor já existente é reconciliado mantendo client id e segredo; o estado `enabled` é
   preservado (provedor desligado pelo administrador continua desligado).
9. **LGPD**: a categoria de log do `IdpDetectExistingBrokerUserAuthenticator` fica desligada (ele grava o e-mail em
   ERROR). A negativa continua auditada pelo `workspace-audit`, sem dado pessoal.

## Alternativas consideradas

| Alternativa | Motivo da rejeição |
|---|---|
| Fluxo padrão `first broker login` | Cria conta para qualquer conta Google |
| Prova por link no e-mail (`idp-email-verification`) | Vincularia conta sem senha, contornando ativação e migração; no ambiente local o e-mail fica no Mailpit |
| Vínculo automático (`idp-auto-link`) | Vincula só pelo e-mail, sem prova de posse da conta |
| `trustEmail=true` | Elevaria `emailVerified` sem necessidade |
| IdP no JSON do realm | O import não tem como omiti-lo sem credenciais (CI) |

## Consequências

- **Positivas**: nenhuma conta nova pela porta social; o Google não dispensa o OTP; só autenticadores nativos.
- **Diferenças para o login por senha**: depois do Google o OTP também vale para `user-importer` (no login por senha ele
  só é pedido no step-up do Cadastro) e não há step-up por LoA.
- **Negativas / riscos**:
  - A tela de confirmação vem com o login da conta de mesmo e-mail, mas o `idp-username-password-form` (26.8.2)
    deixa digitar outro: o vínculo vai para a conta cuja senha foi provada. Restringir exigiria authenticator próprio.
  - O vínculo é gravado depois da senha e antes do OTP (comportamento do Keycloak): sem o OTP não há sessão, mas o
    vínculo fica. Reset de senha ou de OTP **não** o remove: o reset por suspeita de invasão e a resposta a incidente
    precisam remover os vínculos do usuário (README). O vínculo gera o evento `FEDERATED_IDENTITY_LINK` no
    `workspace-audit` (só ids), base para o alerta no Graylog.
  - O botão de vincular do Account Console continua visível e apenas volta com erro.
  - Quem tem `manage-users` (administradores e as contas de serviço do Cadastro) pode criar vínculos pela Admin API.
  - Provedor criado e credenciais removidas depois: o provisioner não o apaga nem o desliga (fazer no Admin Console).
  - Em um realm novo, o botão e os textos próprios só aparecem depois que o provisioner roda.
  - O botão do provedor ganhou estilo no tema (não parecer desabilitado): versão `0.4.1` (cache-busting, ADR-0003).
