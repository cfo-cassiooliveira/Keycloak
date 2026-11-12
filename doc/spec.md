# Spec 001 — Keycloak customizado do Workspace

- **Status**: Em revisão
- **Data**: 2026-11-12 · **Autor**: Cassio Oliveira
- **Projeto/Bounded context**: Identity (`C:\Workspace\keycloak`)

## 1. Problema e objetivo

A Constituição (Art. IV.2) exige que toda autenticação/autorização do Workspace seja feita **exclusivamente
pelo Keycloak**, mas ainda não existe um Keycloak do Workspace. Além disso, o Keycloak "de fábrica":

- registra eventos de login com **dados pessoais** (username, e-mail, IP) no log e no banco, o que conflita com a
  LGPD e com o steering de observabilidade (nada de dado pessoal em log);
- exibe a identidade visual padrão do Keycloak, e não a do Workspace.

**Objetivo**: entregar um Keycloak 26 próprio, reproduzível localmente com um comando, já endurecido pelas regras
de `security.md`, com um ponto de extensão (SPI) pronto para receber customizações e as duas primeiras
customizações: **auditoria de eventos sem dados pessoais** e **tema de login do Workspace em PT-BR**.

**Métricas de sucesso**:
- `docker compose up -d` + `scripts/smoke-test.sh` sobem e validam o Keycloak do zero em < 5 min.
- 0 ocorrência de username, e-mail, IP ou token nos logs de eventos de autenticação (verificado por teste).
- Novo SPI pode ser adicionado ao projeto com build, testes e deploy já funcionando (sem retrabalho de fundação).

## 2. Usuários e permissões

| Perfil | O que pode fazer nesta feature |
|---|---|
| Usuário final | Faz login pela tela com o tema do Workspace |
| Administrador do Keycloak | Acessa o Admin Console (somente via `localhost`), configura realms e consulta eventos |
| Operação/Segurança | Consulta os eventos de auditoria nos logs estruturados (Graylog em etapa futura) |
| Desenvolvedor | Cria novos SPIs no projeto, roda build/testes e sobe o ambiente local |

## 3. Histórias de usuário e critérios de aceite

### US-01 — Subir o Keycloak do Workspace localmente
**Como** desenvolvedor, **quero** subir o Keycloak com as extensões e o realm do Workspace com um comando,
**para** desenvolver e testar integrações sem configuração manual.

```gherkin
Cenário: ambiente sobe do zero
  Dado um clone limpo do projeto e o arquivo .env gerado pelo script
  Quando executo "docker compose up -d"
  Então o Keycloak responde "UP" em /health/ready na porta de gerenciamento
  E o realm "workspace" existe com as políticas de segurança definidas
  E as extensões do Workspace aparecem na lista de providers do servidor

Cenário: segredos ausentes
  Dado que o arquivo .env não existe
  Quando executo "docker compose up -d"
  Então a subida falha com mensagem indicando gerar o .env
  E nenhum segredo padrão/fixo é usado

Cenário: subida repetida não sobrescreve o realm
  Dado que o realm "workspace" já existe no banco
  Quando o Keycloak reinicia
  Então a configuração existente é mantida (import ignora realm existente)
```

### US-02 — Auditoria de autenticação sem dados pessoais
**Como** responsável por segurança, **quero** que cada evento de autenticação e cada ação administrativa gere
um log estruturado sem dados pessoais, **para** auditar e alertar (OWASP A09) respeitando a LGPD.

```gherkin
Cenário: login com sucesso
  Dado um usuário existente no realm "workspace"
  Quando ele faz login com credenciais válidas
  Então é emitido um log de nível INFO com type=LOGIN, realm, client e o id técnico do usuário
  E o log não contém username, e-mail, IP, id de sessão nem token

Cenário: login com credenciais inválidas
  Dado um usuário qualquer
  Quando ele informa senha inválida
  Então é emitido um log de nível WARN com type=LOGIN_ERROR e o código do erro (ex.: invalid_user_credentials)
  E o log não contém o username digitado

Cenário: ação administrativa
  Dado um administrador autenticado no Admin Console
  Quando ele cria, altera ou remove um recurso
  Então é emitido um log INFO com operação, tipo de recurso e id técnico do administrador
  E a representação do recurso (payload) nunca é registrada

Cenário: falha interna no listener
  Dado um evento malformado ou erro ao montar o log
  Quando o listener processa o evento
  Então o erro é registrado uma única vez em nível ERROR, sem dados do evento
  E o fluxo de login do usuário não é interrompido
```

### US-03 — Tela de login com a identidade do Workspace
**Como** usuário final, **quero** ver a tela de login com a identidade visual do Workspace em português,
**para** reconhecer que estou no ambiente correto.

```gherkin
Cenário: tela de login do realm workspace
  Dado o realm "workspace" configurado com o tema "workspace"
  Quando acesso a tela de login de um client do realm
  Então a página usa o tema "workspace" (logo e cores do Workspace)
  E os textos estão em PT-BR por padrão

Cenário: idioma não suportado
  Dado um navegador com idioma diferente de pt-BR e en
  Quando acesso a tela de login
  Então os textos aparecem em PT-BR (idioma padrão do realm)

Cenário: realm master inalterado
  Quando acesso o login do realm "master"
  Então o tema padrão do Keycloak é usado (customização restrita ao realm do Workspace)
```

## 4. Regras de negócio

| ID | Regra |
|---|---|
| RN-01 | Logs de auditoria só contêm campos de uma **lista de permissão**: tipo do evento, realm, client, id técnico do usuário, código de erro, tipo de operação/recurso administrativo, horário (UTC). Todo o resto é descartado. |
| RN-02 | Sucesso → INFO; falha esperada de autenticação (`*_ERROR`) → WARN; falha interna do listener → ERROR. |
| RN-03 | O listener **nunca** interrompe o fluxo de autenticação (falha fechada só se aplica à autorização, não à auditoria). |
| RN-04 | O realm `workspace` não armazena eventos de usuário no banco e não usa o listener padrão `jboss-logging` (ambos expõem dados pessoais). |
| RN-05 | Fluxos permitidos no realm: Authorization Code + PKCE S256, Client Credentials e Device Grant. Implicit e Direct Access Grants desabilitados. |
| RN-06 | Proteção contra força bruta habilitada no realm; política de senha forte. |
| RN-07 | Nenhum segredo versionado: admin inicial e senha do banco vêm do `.env` gerado localmente. |

## 5. Requisitos não funcionais

- **Performance**: o listener não adiciona latência perceptível ao login (processamento síncrono em memória, sem I/O remoto).
- **Disponibilidade**: ambiente local (dev). Produção, cluster e HA ficam fora deste escopo.
- **Dados pessoais (LGPD)**: o Keycloak armazena dados de usuários no seu próprio banco (PostgreSQL dedicado,
  Art. III.4). Logs de auditoria seguem a minimização (RN-01). Retenção de logs definida quando houver Graylog.
- **Auditoria**: login, falhas de login, logout, troca de senha, ações administrativas.
- **Segurança**: Admin Console e porta de gerenciamento publicados apenas em `127.0.0.1`; imagens fixadas por tag + digest.
- **Compatibilidade**: extensões compiladas para Java 21 e Keycloak 26.8.x (SPIs internos podem mudar entre versões).

## 6. Fora de escopo

- Alterações em outros projetos do Workspace (`legado`, `graylog`, `sonarqube`, sites) — decisão do responsável técnico.
- Envio dos logs ao Graylog e streams/alertas (dependem de alterar o projeto `graylog`).
- Registro no SonarQube local (depende de alterar `sonarqube/provisioning/projects.json`).
- Migração dos usuários do `legado` (importação de hashes `pbkdf2-sha512`, User Storage SPI de migração, BFF).
- Clients de aplicações (cada aplicação cria o seu quando for integrada).
- Publicação de eventos no RabbitMQ, temas de e-mail/conta/admin, autenticadores e required actions customizados,
  validadores de perfil, deploy em produção/HTTPS próprio/cluster.

## 7. Premissas e dúvidas em aberto

- [Premissa] Projeto novo e independente em `C:\Workspace\keycloak` (tipo "infraestrutura + extensões"), como `graylog`/`sonarqube`.
- [Premissa] Keycloak **26.8.2** (última da documentação) e PostgreSQL 17 dedicado.
- [Premissa] Primeiras customizações: Event Listener SPI (auditoria) e Theme (login). Escolhidas por exigência da
  LGPD/observabilidade e por serem visíveis já na primeira entrega; outros SPIs entram por novas specs.
- [Premissa] Realm de dev: `workspace`, idioma padrão `pt-BR`, suportados `pt-BR` e `en`.
- [Premissa] Logo/cores: placeholders neutros do Workspace até haver identidade visual oficial.
- [Dúvida] Registrar IP nos eventos de falha de login (interesse legítimo de segurança) com retenção curta? Hoje: não registrar. — Responsável técnico.
- [Dúvida] Quando liberar a integração com Graylog e SonarQube (alteração nos respectivos projetos)? — Responsável técnico.
