# ADR-0003: Tema de login em Bootstrap 5 sobre o tema `base`

- **Status**: Proposto
- **Data**: 2026-11-12
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. II.1, Art. IV.1, Art. IV.7

## Contexto

O tema `workspace` só sobrescrevia cores do `keycloak.v2` (PatternFly 5). Foi pedido um redesenho responsivo usando
as estruturas do **Bootstrap** (grid, formulários, botões), com UX melhor e a paleta do Workspace (céu `#7FC1EF`,
pervinca `#7F88EF`, água `#80EBE0`) e o logo CFO.

## Decisão

1. O tema herda do **`base`** (templates sem estilo) em vez do `keycloak.v2`, e mapeia todas as classes `kc*` do
   `theme.properties` para classes do Bootstrap. Assim **todas** as telas do fluxo (troca de senha, OTP, erro, info)
   ficam no mesmo padrão, e não só a de login.
2. `template.ftl` (layout em duas colunas no desktop, barra compacta no celular) e `login.ftl` são próprios. O layout mantém
   os comportamentos do `base` 26.8: polling de sessão, verificação da sessão de autenticação, importmap de WebAuthn,
   "tentar outro jeito" e troca de organização.
3. **Só o CSS do Bootstrap**, sem o JS: o idioma é um seletor de links (sem dropdown) e os ícones são máscaras SVG no CSS.
   O JS próprio (`workspace.js`) cuida do estado de envio, aviso de Caps Lock e links de provedor clicáveis uma vez.
4. Bootstrap 5.3.8 vem do **WebJar** `org.webjars.npm:bootstrap` (versão fixada no `pom.xml`, extraído no build, nunca
   copiado para o repositório). Fonte Manrope (OFL 1.1) auto-hospedada no tema, sem Google Fonts (o IP do usuário não sai
   para terceiros, LGPD), com integridade conferida contra o hash publicado no npm.
5. Ações e links usam tons escuros derivados da pervinca (`#4A52C9`, contraste ≈ 6,3:1 com branco); as cores puras da
   paleta ficam em elementos decorativos e de foco, porque texto branco sobre elas não atinge o WCAG AA.
6. **Cache-busting**: o Keycloak serve recursos de tema com `Cache-Control: max-age=2592000` num caminho que só muda com a
   versão do Keycloak. Os links do tema levam `?v=<versão do projeto>` (filtrada pelo Maven no `theme.properties`); toda
   mudança de CSS/JS/imagem exige incrementar a versão do `pom.xml`.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| Continuar no `keycloak.v2` só com CSS | Menos código | PatternFly, não Bootstrap; layout limitado | Não atende o pedido |
| Sobrescrever só o `login.ftl` | Menos templates | Outras telas ficariam sem estilo/inconsistentes | Fluxo visualmente quebrado |
| Bootstrap via CDN | Sem build | Dependência externa em tempo de execução; IP do usuário para terceiros | Supply chain (A03) e LGPD |
| Bootstrap JS (bundle) | Dropdowns prontos | +80 KB para dois componentes simples | YAGNI |
| Keycloakify (React) | Temas ricos | Toolchain Node, outra stack | Complexidade desproporcional |

## Consequências

- **Positivas**: telas responsivas e consistentes; dependências fixadas e sem chamadas externas; erro mostrado no campo.
- **Negativas / riscos**: templates próprios precisam ser revisados a cada upgrade do Keycloak (comparar com o `base`
  da nova versão); esquecer de incrementar a versão deixa usuários com CSS antigo por até 30 dias.
- **Ações decorrentes**: testes de tema (`ThemeMessagesTest`, `WorkspaceRealmIT`) cobrem herança, Bootstrap extraído,
  recursos servidos, versão filtrada, classes do Bootstrap e erro junto ao campo.
