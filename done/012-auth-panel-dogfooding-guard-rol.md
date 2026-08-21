# 012 — Auth del panel (dogfooding) + guard de rol

## Objetivo
El panel de administración se autentica usando el propio auth-core-mc (dogfooding), y cada request a los endpoints de administración pasa por un guard que verifica el rol del ticket 011. Nace de `docs/definiciones/panel-administracion-clientes.md` (HU-3, diseño técnico, diagrama de secuencia "acceso al panel con control de rol").

**Depende de:** ticket 011 (RBAC).

## Criterios de aceptación (TDD)
- Un usuario del panel inicia sesión vía el flujo de login ya existente de auth-core-mc; el JWT resultante incluye claim de rol y tenant_id.
- El guard de rol intercepta cada request a rutas de administración y aplica la regla: autorizado si `rol=platform_admin` O (`rol=tenant_admin` Y `tenant_id` coincide).
- Requests sin JWT válido, o con rol insuficiente, responden 401/403 según corresponda — no 500 ni comportamiento ambiguo.
- Documentado en `docs/ARQUITECTURA.md` el riesgo de dependencia circular (si auth-core-mc está degradado, el panel también lo está) — mitigado por el ticket 018 (break-glass).

## Hecho (TDD real: rojo → verde)
- `AdminClaimsCustomizer` estampa `role`/`tenant_id` en cada access token — wireado en `TokenGeneratorConfig`.
- **Hallazgo real (bytecode, no asumido)**: un `.put()` genérico en el `OAuth2TokenContext` no sobrevive a `JwtGenerator`'s reconstrucción interna del `JwtEncodingContext` — solo campos reconocidos se copian. Fix: se reutiliza `authorizationGrant` (uno de los campos que sí sobrevive) para pasar el `User`, dejando `principal` intacto para no afectar `sub`.
- `AdminRoleAuthoritiesConverter` mapea el claim `role` a autoridad Spring (`ROLE_TENANT_ADMIN`/`ROLE_PLATFORM_ADMIN`).
- **Hallazgo real, preexistente**: `SecurityConfig` nunca tuvo el resource server (`.oauth2ResourceServer(...)`) conectado — ningún endpoint podía autenticarse de verdad con un Bearer token en producción hasta ahora, solo vía `@WithMockUser` en tests. Conectado, más la regla `/api/v1/admin/**` → rol requerido.
- **Regresión real encontrada y arreglada**: los 9 `@WebMvcTest` que importan `SecurityConfig` se rompieron (sin `JwtDecoder` en el contexto slice) — arreglado con `@MockitoBean private JwtDecoder jwtDecoder;` en cada uno.
- `AdminRoleGateIntegrationTest` (`@SpringBootTest`, Testcontainers real): prueba de punta a punta con login real, token real, request Bearer real contra un endpoint ya existente — no mocks.
- 196/196 tests en verde (187 previos + 9 nuevos).
- Deliberadamente fuera de este ticket: endpoints admin reales bajo `/api/v1/admin/**` (llegan en el ticket 013) — la regla de ruta se probó genéricamente, no contra un admin endpoint real todavía.
