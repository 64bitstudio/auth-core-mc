# Arquitectura de auth-core-mc

> Este documento explica **cómo** se comunican las partes del sistema y, sobre todo, **por qué** se tomó cada decisión. Se actualiza cada vez que se completa una tarea que cambia la arquitectura.

## ¿Qué es este proyecto?

Un servicio centralizado de autenticación y autorización (identidad) que cualquier otro proyecto tuyo puede usar en vez de reinventar login, registro, 2FA, recuperación de contraseña, etc. Piensa en él como "tu propio Auth0/Keycloak", pero controlado por ti y con la posibilidad de ofrecerlo como servicio a clientes.

## Decisiones de arquitectura y su porqué

### 1. Modelo: multi-tenant + clonable a instancia dedicada
**Qué es:** Un único despliegue del servicio puede alojar múltiples "tenants" (proyectos/clientes), cada uno con sus propios usuarios, configuración de login social, y parámetros (tiempos de expiración, colores de marca, etc.) — de forma aislada lógicamente por `tenant_id`.

**Por qué:** Mantener un solo servicio corriendo es más barato y fácil de mantener que uno por proyecto. Pero se diseña desde el día uno para que un tenant pueda "exportarse" 1:1 a su propia instancia aislada (su propia base de datos, su propio contenedor) si un proyecto futuro necesita aislamiento total — por ejemplo, un cliente que paga por exclusividad, o un requisito regulatorio. Sin este diseño desde el inicio, separar un tenant después sería mucho más costoso.

### 2. Stack: Java + Spring Boot + Spring Authorization Server
**Por qué:** Spring Authorization Server es la implementación oficial y mantenida por el equipo de Spring de un servidor OAuth2/OIDC completo — no es una librería genérica adaptada, es un servidor de autorización real. Ahorra meses de trabajo en implementar correctamente algo tan sensible como OAuth2 desde cero.

### 3. Grants OAuth2 soportados: Authorization Code + PKCE, y login directo first-party
**Por qué:** Authorization Code + PKCE es el estándar recomendado por la especificación OAuth2 (RFC 6749 + BCP), usado cuando hay una pantalla de login intermedia (redirect). Pero como este servicio también debe poder consumirse "solo por API" (según tu requerimiento original), se añade un segundo endpoint de login directo (email/teléfono + password → token) — **restringido a clientes marcados como "first-party"** (aplicaciones tuyas, no de terceros), porque exponerlo a cualquier tercero rompería el modelo de seguridad de OAuth2 (el usuario nunca vería ni confiaría el password a la app cliente).

### 4. Base de datos: PostgreSQL + Redis
**Por qué:** PostgreSQL para todo dato persistente (usuarios, tenants, clientes OAuth2). Redis para todo lo que necesita ser rápido y con expiración natural: revocación instantánea de refresh tokens, rate limiting de intentos de login/OTP, y prevención de reuso de códigos TOTP dentro de su ventana de validez. Sin Redis, revocar una sesión requeriría esperar a que el JWT expire por sí solo — inaceptable para un logout de seguridad.

### 5. Hash de contraseñas: Argon2id
**Por qué:** Es el ganador de la Password Hashing Competition y el estándar recomendado actualmente por OWASP, más resistente que bcrypt a ataques acelerados por GPU/ASIC.

### 6. Cifrado de datos personales: estándar (hash + disco + TLS), EXCEPTO credenciales de terceros
**Por qué la excepción:** Email y teléfono se protegen con cifrado de disco + TLS en tránsito (suficiente para LFPDPPP y buenas prácticas). Pero el `client_secret` de cada integración social (Google/Facebook/Apple) por tenant se cifra **a nivel de aplicación** con una clave propia, porque a diferencia de una contraseña de usuario (que solo necesita compararse, nunca leerse), el `client_secret` **debe poder recuperarse en claro** para autenticar las llamadas al proveedor social. Un hash irreversible no serviría aquí.

### 7. Proveedores externos: Resend (correo) y Twilio (SMS)
**Por qué:** Elegidos por simplicidad de integración y buena capa gratuita/documentación. Ambos quedan detrás de una interfaz propia (no acoplados directamente en la lógica de negocio) para poder cambiarlos sin tocar el resto del sistema.

### 8. UI: aplicación web (no React Native)
**Por qué:** Un servidor de autorización OAuth2 estándar (Auth0, Keycloak, Spring Authorization Server) sirve sus pantallas de login/consentimiento como **web**, porque cualquier cliente —incluida una app móvil— puede abrir esa pantalla en un navegador/WebView durante el flujo de redirect. No hay necesidad de una app nativa dedicada para esto.

## Cómo se comunican las partes (vista general)

```
┌─────────────┐        HTTPS/OAuth2         ┌──────────────────────┐
│  Apps/Sitios │ ───────────────────────────▶│   auth-core-mc (API)  │
│  de terceros │◀─────────────────────────── │  Spring Boot + Spring │
│  o propios   │      tokens JWT / OIDC       │  Authorization Server │
└─────────────┘                              └───────────┬───────────┘
                                                            │
                              ┌─────────────────────────────┼─────────────────────────┐
                              ▼                             ▼                         ▼
                      ┌───────────────┐            ┌───────────────┐         ┌───────────────┐
                      │  PostgreSQL   │            │     Redis     │         │ Resend/Twilio │
                      │ (usuarios,    │            │ (revocación,  │         │ (correo/SMS   │
                      │  tenants,     │            │ rate-limit,   │         │  OTP, avisos) │
                      │  clientes)    │            │ anti-reuso    │         └───────────────┘
                      └───────────────┘            │  de TOTP)     │
                                                     └───────────────┘
```

La **UI web** (login/registro/2FA/reset) es un cliente más de esta misma API — no tiene lógica de negocio propia, solo presenta formularios y llama a los mismos endpoints que cualquier integración externa usaría.

## Ticket 002: registro, login, y cómo se identifica el tenant

- **`X-Client-Id`**: cada request a `/register` o `/login` trae este header con el `client_id` de un `IdentityClient` ya registrado; el servidor resuelve su `tenant` a partir de ahí. Es una decisión deliberada para no acoplar la API "directa" (pensada para ser llamada por código, sin UI intermedia) a un mecanismo de sesión o cookie. El flujo OAuth2 real (ticket `007`) es una superficie distinta y usa el parámetro estándar `client_id`, no este header.
- **Por qué el login no emite tokens todavía**: emitir JWT/OAuth2 tokens "de verdad" antes de que exista el servidor de autorización (ticket `007`) habría significado escribir código de emisión de tokens que luego se descarta. `AuthenticationService` termina su responsabilidad en "estas credenciales son válidas para este usuario" — ticket `007` la llama y decide qué token emitir, con qué TTL (parametrizable por tenant, ver tabla `tenant`).
- **Por qué las excepciones de dominio (`WeakPasswordException`, `DuplicateIdentifierException`, etc.) viven en `service`, no en `web`**: son reglas de negocio, no detalles HTTP — `GlobalExceptionHandler` es la única pieza que sabe traducirlas a códigos HTTP, así que si mañana este mismo dominio se expone por otro medio (un job asíncrono, un CLI interno), las reglas siguen siendo las mismas.
- **Rate limiting en Redis, no en la base de datos**: un contador que necesita expirar solo (ventana de 15 minutos) y ser barato de leer en cada intento de login es exactamente para lo que sirve Redis — guardar esto en Postgres obligaría a un job de limpieza y sería más lento de consultar.
- **`@WebMvcTest` no carga tu `@Configuration` de seguridad sola**: solo escanea beans de la capa web (controllers, `@ControllerAdvice`, etc.). Sin `@Import(SecurityConfig.class)` explícito en el test, Spring Security cae a sus valores por defecto (CSRF activo, todo requiere autenticación) y cada request en el test recibe `403`, sin relación con la lógica que se quiere probar.

## Ticket 003: verificación de correo y cambio de correo

- **`RedisTokenStore` genérico, no tres implementaciones separadas**: verificación de cuenta, cambio de correo, y recuperación de password (ticket `004`) son la misma forma — "emitir un token de un solo uso que expira, mandarlo por correo, consumirlo cuando llega". Un solo componente namespaced por `purpose` evita triplicar la misma lógica de Redis.
- **`EmailSender` como interfaz, `ResendEmailSender` como única implementación real**: la lógica de negocio (`EmailVerificationService`, `EmailChangeService`) nunca importa nada de Resend directamente. Cambiar de proveedor de correo en el futuro es escribir una clase nueva, no tocar servicios existentes.
- **`ResendEmailSender` falla ruidosamente sin `RESEND_API_KEY`**, no en silencio — consistente con la filosofía del hook `silent-failure-guard`: una dependencia externa requerida que no está configurada debe fallar explícito, no fingir que envió el correo.
- **Confianza temporal en `/verify-email/request` y `/change-email/request`**: como ticket `007` (tokens OAuth2 reales) todavía no existe, estos dos endpoints reciben el `userId` directamente del llamador en vez de leerlo de un token de acceso autenticado. Es una decisión consciente y acotada — ver la advertencia en `docs/API.md` y el Javadoc de `TenantScopedUserResolver` — no un descuido.
- **Spring Boot 4.1 separó `RestClient.Builder` de `webmvc`**: en Boot 3.x venía "gratis" con el starter web; en 4.1 hace falta el starter `spring-boot-starter-restclient` explícito o Spring no encuentra el bean al construir cualquier cliente HTTP (como `ResendEmailSender`).

## Ticket 004: recuperación de contraseña

- **`requestReset` nunca lanza excepción, nunca se comporta distinto entre "existe" y "no existe"**: es el único endpoint del proyecto donde el llamador solo aporta una *adivinanza* (un email/teléfono), a diferencia de `/verify-email/request` donde ya aporta un `userId` que se asume conocido. Por eso aquí ni siquiera el rate-limit puede ser visible — el cooldown se activa igual exista o no la cuenta, y la respuesta HTTP es siempre `202`.
- **`SmsSender`/`TwilioSmsSender` nacen en este ticket, no en el `005`**: un usuario que se registró solo con teléfono necesita recuperar su contraseña por SMS, y ese caso ya existía antes de que 2FA (que también usará Twilio) llegara. El ticket `005` reutiliza esta misma interfaz para OTP.
- **Preferencia email > SMS cuando el usuario tiene ambos**: decisión simple y documentada aquí — el email es gratis de enviar (Resend) y no depende de saldo/costo por mensaje (Twilio), así que se usa primero si está disponible.

## Ticket 005: 2FA (OTP + TOTP)

- **`SecretEncryptor` (AES-256-GCM) nace aquí, no en el ticket 006**: la columna `totp_secret_encrypted` existía desde el ticket 001, pero nada la cifraba realmente hasta ahora. Un secreto TOTP necesita ser legible en claro para calcular códigos (igual que el `client_secret` social del ticket 006) — por eso NO se hashea como una contraseña. El default de la clave (`app.secret-encryption-key`) es de solo-desarrollo y está documentado en voz alta como inseguro para producción — ver su Javadoc y `docs/README.md`.
- **`Totp` implementado a mano (RFC 6238), sin librería externa**: es un algoritmo pequeño y muy bien especificado (HMAC-SHA1 + Base32); no justificaba una dependencia nueva.
- **Reuso de `LoginRateLimiter` para proteger el OTP contra fuerza bruta**: un código de 6 dígitos solo tiene 1,000,000 de combinaciones — necesita exactamente la misma defensa "N intentos por ventana" que un password de login. El nombre de la clase es histórico (nació antes que 2FA), pero su comportamiento generaliza sin cambios.
- **Protección anti-reuso de TOTP vía Redis, separada de la tolerancia a desfase de reloj**: `Totp.verify` acepta ±1 ventana de 30s (desfase de reloj normal entre servidor y app autenticadora), pero una vez que un código de una ventana específica se usó, `TotpService` lo bloquea para esa misma ventana en Redis — así que la tolerancia de desfase no se convierte en una ventana de reuso.
- **`/2fa/**` hereda el mismo límite de confianza temporal que ticket 003** (`userId` del llamador, sin bearer token real todavía) — ver la advertencia ya documentada en `docs/API.md`.

## Ticket 006: login social — qué se construyó y qué se pospuso a propósito

- **Se construyó**: la API de configuración por tenant (`/identity-providers/*`) — habilitar/deshabilitar Google o Facebook, guardar `client_id` + `client_secret` (cifrado con `SecretEncryptor`, la pieza que nace en el ticket 005), y nunca exponer el secreto de vuelta. Cubre 2 de los 3 criterios de aceptación del ticket.
- **Se pospuso, con razón documentada**: el flujo real de redirect+callback de OAuth2 (que el usuario haga clic en "Entrar con Google" y termine autenticado). Al construir las credenciales reales de Google/Facebook para probar esto, quedó claro un acoplamiento que no era obvio al escribir el backlog: ese flujo termina en "el usuario ya está autenticado, ¿ahora qué le devuelvo?" — y la respuesta es o bien un token real (ticket `007`, no existe todavía) o una sesión que la UI web consume (ticket `009`, tampoco existe). Construir el redirect+callback ahora habría significado escribir código que se descarta o se reescribe en cuanto exista uno de los dos. Se documenta como hallazgo de secuenciación, no como trabajo saltado.
- **Apple explícitamente rechazado, no solo "no implementado"**: `TenantIdentityProviderService.configure` lanza `UnsupportedProviderException` para `APPLE` en vez de aceptarlo silenciosamente y fallar después — Apple Sign In no usa `client_id`/`client_secret`, necesita una clave privada de una membresía paga de Apple Developer Program.
- **`/identity-providers/*` es el primer endpoint de este proyecto que NO está en `permitAll`**: es una acción de administración real (configurar secretos OAuth), a diferencia de los endpoints con "límite de confianza temporal" de tickets `003`/`005` (cuyo peor caso es spam de correo/SMS). Sin autenticación de tenant-admin todavía, se deja en el comportamiento por defecto de Spring Security (401 para todos) — fail-closed a propósito.
- **Credenciales reales de Google y Facebook ya existen** (proyecto/app dedicados `auth-core-mc`, creados junto al Product Owner vía navegador, con su confirmación explícita para aceptar los términos de cada plataforma) y viven en `backend/.env` (gitignored) — ver `docs/README.md` para cómo usarlas cuando se retome el flujo de redirect+callback.

## Ticket 007: servidor de autorización OAuth2 — grant directo, tokens reales, y tres bugs que solo aparecieron en vivo

- **Dos caminos para obtener un token, no uno**: `/oauth2/authorize` + `/oauth2/token` (Authorization Code + PKCE estándar, para third-party o para cuando exista UI propia) y `/api/v1/login` (grant directo, sin redirect, solo para clientes marcados `is_first_party = true`). Ambos terminan generando el JWT con el mismo `JwtGenerator` — no hay dos implementaciones de firmado de tokens, solo dos puntos de entrada.
- **`DirectTokenService` construye el `OAuth2TokenContext` a mano, no reusa el filtro `/oauth2/token`**: no hay un `HttpServletRequest` real pasando por la cadena de filtros de Spring Authorization Server en el login directo, así que no hay a quién delegarle la generación del token. Se llama al mismo `JwtGenerator` directamente, con un `AuthorizationGrantType` propio (`urn:mcortes:params:oauth:grant-type:direct`) que existe solo para etiquetar el contexto — nunca se registra como grant soportado en `TenantAwareRegisteredClientRepository`, porque nunca pasa por la validación de grants del endpoint estándar `/oauth2/token`.
- **`AuthorizationServerContextHolder` hay que poblarlo a mano fuera de un request HTTP real**: normalmente lo llena un filtro de Spring Authorization Server antes de que el código de negocio lo necesite; como `DirectTokenService` no corre dentro de ese filtro, tiene que crear su propio `SimpleAuthorizationServerContext` y ponerlo/quitarlo del `ThreadLocal` manualmente alrededor de la generación (ver Javadoc de `SimpleAuthorizationServerContext`).
- **Refresh token opaco (SHA-256), no JWT**: a diferencia del access token, el refresh token no necesita ser autocontenido ni verificable sin ir a la base de datos — de hecho, SÍ necesita poder revocarse consultando la base de datos en cada uso. Un string aleatorio de alta entropía hasheado (no Argon2id — ver `TokenHasher`, la razón de usar SHA-256 aquí y no el mismo hash que las contraseñas está documentada ahí) y guardado en `refresh_token` (tabla que ya existía desde ticket `001`) resuelve esto sin infraestructura nueva.
- **El refresh token no rota en cada uso** (`DirectTokenService.refresh` devuelve el mismo `refreshToken` de entrada): simplificación deliberada, documentada en el código — rotación es un endurecimiento natural para una iteración futura, no bloqueante para cerrar este ticket.
- **`Spring Security 7.1` eliminó `OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(HttpSecurity)`**: confirmado empíricamente vía `javap` contra el jar real (no asumido de memoria de entrenamiento, que en este stack tan reciente resulta poco confiable). El reemplazo es el método DSL `HttpSecurity.oauth2AuthorizationServer(Customizer<OAuth2AuthorizationServerConfigurer>)`, el mismo patrón que ya existía para `.oauth2Login(...)`/`.oauth2ResourceServer(...)`.

### Tres bugs que ningún test automatizado detectó — solo aparecieron corriendo la app de verdad
Los 169 tests (unitarios + slice) pasaban en verde con el código de este ticket, pero por el riesgo de usar una API tan nueva se decidió además levantar la app con `bootRun` y probar los endpoints reales antes de dar por cerrado el ticket. Esto encontró tres problemas que ninguna prueba automatizada tocaba:

1. **Colisión de nombre de proyecto en `compose.yaml`**: sin un `name:` explícito, Docker Compose usa el nombre de la carpeta (`backend`) como identificador de proyecto. Otro proyecto no relacionado en esta misma máquina también tiene su backend en una carpeta llamada `backend`. Spring vio contenedores con ese nombre "ya corriendo" y no levantó los propios — la app se conectó al Postgres del OTRO proyecto. Flyway se negó correctamente a migrar un esquema ajeno no vacío (ningún dato se dañó), pero el síntoma inicial fue confuso. Fix: `name: auth-core-mc` explícito en `compose.yaml`. Ningún test lo detecta porque los tests usan Testcontainers (efímero, sin este problema de nombres) — esta clase de bug solo existe en el camino de `bootRun`.
2. **NPE en el arranque de `/oauth2/**`**: ver el Javadoc de `AuthorizationServerConfig` — un `getEndpointsMatcher()` llamado sobre una instancia del configurer nunca conectada al `HttpSecurity` real. `@SpringBootTest`'s `contextLoads()` no lo detecta porque nunca envía una request HTTP real a través de la cadena de filtros; hizo falta un `curl` real a `/oauth2/jwks` para ver el 500.
3. **`Argon2PasswordEncoder` sin BouncyCastle en runtime**: usado desde el ticket `002`, nunca falló en ningún test porque ninguno llama `.encode()`/`.matches()` sobre el bean real (siempre mockeado o nunca ejercitado con una petición HTTP completa). Al hacer el primer `POST /api/v1/register` real contra la app viva, salió `NoClassDefFoundError: Argon2Parameters$Builder` — y ese error además quedó disfrazado como un `401` genérico, porque el forward interno de Tomcat a `/error` no está en `permitAll` de `SecurityConfig`, así que Spring Security lo interceptaba antes de que el body real del error llegara a la respuesta. Hubo que activar logging DEBUG de Spring Security para ver la excepción real debajo del 401. Fix: `runtimeOnly 'org.bouncycastle:bcprov-jdk18on:1.85.2'` en `build.gradle`.

**Lección para los siguientes tickets**: para cualquier pieza que dependa de una API muy reciente o poco probada en este stack (Spring Boot 4.1 / Spring Security 7.1), correr la app en vivo antes de cerrar el ticket sigue siendo parte del criterio de "hecho", no un paso opcional — el suite de tests por sí solo no habría detectado ninguno de estos tres problemas.

## Ticket 008: multi-tenencia — probar el aislamiento, y clonar un tenant a instancia dedicada

- **El aislamiento por tenant ya existía desde el ticket `001`; este ticket lo demuestra, no lo construye**: cada consulta de negocio real (`findByTenantAndEmail`, `findByTenantAndPhone`, `findByTenantAndProvider`) ya recibía el `Tenant` como parámetro desde que se escribieron esos repositorios. Lo que faltaba era una prueba que lo demostrara *holísticamente*, en un solo lugar, en vez de confiar en que cada test de repositorio lo cubriera por accidente — ver `TenantIsolationTest` (paquete `repository`), que crea dos tenants con el mismo email/teléfono/proveedor social y prueba que cada consulta nunca cruza al otro tenant.
- **Dos lookups son deliberadamente globales, no un descuido**: `IdentityClientRepository.findByClientId` y `RefreshTokenRepository.findByTokenHash` no reciben un `Tenant` como filtro. Es correcto: `client_id` es precisamente el mecanismo que resuelve a qué tenant pertenece una request (ver `ClientContextResolver`, ticket `002`) — no puede depender de ya conocer el tenant. Y `token_hash` es un SHA-256 de un valor aleatorio de alta entropía — añadir un filtro de tenant no aportaría aislamiento real, porque poseer el token crudo ya es la única forma de llegar a esa fila. `TenantIsolationTest` prueba explícitamente que, aunque el lookup no está filtrado, la fila que devuelve siempre pertenece al tenant correcto (nunca mezcla usuarios/clientes de tenants distintos).
- **Clonado a instancia dedicada: `export-tenant.sh` / `import-tenant.sh`, no `pg_dump`**: el `pg_dump` estándar no soporta filtrar un dump de datos por fila (`--where` no existe en pg_dump real, a pesar de ser un pedido común) — solo dumpea tablas completas. La alternativa construida aquí usa `psql \copy (SELECT ... WHERE tenant_id = ...) TO STDOUT` por tabla, envuelto en los mismos bloques `COPY <tabla> (<columnas>) FROM stdin; ... \.` que `pg_dump` generaría para una tabla completa — así que el archivo resultante es SQL corriente, reproducible con cualquier `psql -f`, no un formato inventado.
- **Orden de tablas por dependencia de FK**: `tenant` → `app_user`/`tenant_identity_provider`/`identity_client` → `refresh_token` (depende de `app_user` y de `identity_client`). El script exporta e importa en ese orden exacto.
- **Bug real encontrado solo al probar en vivo (no en ningún test)**: el diseño original pasaba el `tenant_id` como variable de psql (`-v tenant_id=... ` + `:'tenant_id'` dentro de la subconsulta). Al correrlo de verdad contra el Postgres del ticket `007`, psql falló con `syntax error at or near ":"` — la sustitución de variables de psql no se aplica de forma confiable dentro del argumento de `\copy (...)`, que tiene su propio tokenizador ad-hoc en vez de pasar por el parser SQL normal. Se corrigió validando el `tenant_id` como UUID bien formado en bash (`[[ "$TENANT_ID" =~ ^[0-9a-fA-F]{8}-... ]]`) e interpolándolo directamente en el texto de la consulta — seguro únicamente porque ya se validó su forma exacta antes.
- **Verificado en vivo, extremo a extremo, no solo con tests**: se exportó el tenant `Acme` real (creado durante el smoke-test del ticket `007`, con su usuario, `identity_client`, y un refresh token ya revocado) desde el Postgres de desarrollo, se levantó un segundo contenedor Postgres nuevo y separado (simulando una instancia dedicada real), se le aplicaron las migraciones `V1`+`V2`, se importó el dump, y se confirmó que los datos —incluyendo el array `redirect_uris` y el booleano `revoked`— llegaron intactos y con las relaciones FK correctas. Este patrón de "probar en vivo antes de cerrar, no solo confiar en los tests" viene del ticket `007` y aquí volvió a pagar: encontró el bug de `:'tenant_id'` que ningún test unitario habría detectado (los tests no ejercitan scripts de bash).
- **Por qué no se automatizó con JUnit**: los scripts son bash + `psql`, fuera del alcance natural de Gradle/JUnit. Se optó por el mismo criterio que las credenciales OAuth reales del ticket `006` o el smoke-test del `007`: una verificación real y documentada (arriba) en vez de un test frágil que shellee fuera del proceso de la JVM. El criterio de aceptación TDD del ticket sí se cumplió íntegramente para la parte que es naturalmente unitaria (el aislamiento por tenant).
- **Destino cloud sigue sin decidir** (ver estado del proyecto): el proceso de clonado es intencionalmente agnóstico de proveedor — cualquier Postgres alcanzable por `psql` sirve como origen o destino, así que no bloquea nada de esta decisión pendiente.

## Ticket 009: UI web — decisiones, lo construido, y lo deliberadamente pospuesto

- **Thymeleaf server-rendered, no un SPA separado**: ya era la dirección implícita desde la decisión 8 ("UI: aplicación web... sirve sus pantallas de login/consentimiento como web") — un segundo deployable con su propio build de JS habría significado CORS, dos lugares para el theming, y complejidad que este proyecto no necesita todavía. Ver `docs/COMPONENTES.md` para el detalle completo.
- **`client_id` como query param en las páginas, no el header `X-Client-Id`**: una navegación de página completa no puede fijar un header propio — solo el JS de la página, para sus propias llamadas a `fetch`, puede hacerlo. Toda página que necesita theming lleva `?client_id=...`, igual que `/oauth2/authorize` (ticket `007`) por la misma razón exacta.
- **Los enlaces emailados (verificación, cambio de correo, reset) ahora apuntan a páginas `/ui/**`, no a los endpoints JSON crudos**: antes del ticket `009`, `VerificationLinkFactory` construía enlaces a `/api/v1/verify-email/confirm?token=...` — una persona haciendo clic en ese enlace veía un JSON crudo en vez de una página. Se corrigió apuntando a `/ui/verify-email/confirm` (y análogos), que llaman al mismo endpoint JSON vía `fetch` una vez cargados. Los endpoints JSON en sí no cambiaron.
- **`/ui/cuenta` usa `sessionStorage`, no una sesión real de Spring Security**: ver `docs/COMPONENTES.md` para el razonamiento completo — es una extensión deliberada de la misma frontera de confianza temporal que los tickets `003`/`005` ya aceptaron para estos mismos endpoints (`userId` proporcionado por el llamador, no derivado de un token de acceso autenticado), no un nuevo hueco introducido por este ticket.
- **Hallazgo de secuenciación, igual que el del ticket `006`**: este ticket construye una UI real para el grant directo (`/api/v1/login` con tokens del ticket `007`) y para los flujos de gestión de cuenta — pero NO integra esa UI con el flujo `/oauth2/authorize` (Authorization Code + PKCE) de Spring Authorization Server, cuyo `formLogin` (ver `AuthorizationServerConfig`) sigue usando el formulario por defecto de Spring Security, sin un `UserDetailsService` real conectado a `app_user`/Argon2id. Resolverlo bien requiere decidir cómo un formulario de login **compartido entre tenants** sabe contra qué tenant autenticar (el email es único por tenant, no global) — un diseño genuino, no solo una implementación, que se documenta aquí como pendiente en vez de resolverse con una suposición apresurada. La UI de este ticket SÍ resuelve el flujo de redirect+callback de login social que el ticket `006` había pospuesto **en el sentido de que ahora existe una sesión de conveniencia post-login/registro** — pero la integración con Google/Facebook (`oauth2Login`) en sí sigue sin construirse; sigue siendo trabajo futuro, no de este ticket.
- **Sin toolchain de JS**: no se agregó npm/axe-core/pa11y para la auditoría de accesibilidad — se hizo una revisión manual contra WCAG (contraste, labels, foco visible, `aria-live`). Documentado como hueco de cobertura conocido en `docs/COMPONENTES.md`, no silenciado.
- **Verificado en vivo, en un navegador real**: además de `UiPagesControllerTest` (MockMvc), se probó el flujo registro→cuenta→reenvío de verificación (respetando el cooldown)→enroll de TOTP→login→cuenta contra la app corriendo de verdad, confirmando que el theming, los formularios, y las llamadas `fetch` con `X-Client-Id` funcionan end-to-end, no solo a nivel de test de controlador.

## Ticket 010: CI/CD — por qué un self-hosted runner, no un runner de GitHub en la nube

- **El problema real**: el criterio de aceptación pide que el pipeline falle si el Quality Gate de SonarQube está en rojo, contra la instancia de SonarQube ya establecida en `~/dev-infra` (decisión previa, ver `docs/dev-infra` / memoria del proyecto). Un runner de GitHub Actions en la nube no puede alcanzar `http://localhost:9000` en la Mac del Product Owner — no es un detalle de implementación, es una restricción física de red.
- **Opciones evaluadas con el Product Owner** (se le preguntó explícitamente, no se asumió): (a) self-hosted runner en su Mac, (b) CI sin SonarQube por ahora (solo build+tests+Telegram), (c) migrar a SonarCloud. Eligió (a).
- **Self-hosted runner, no un servidor de CI aparte**: sigue siendo GitHub Actions (mismo `ci.yml`, misma orquestación) — solo cambia DÓNDE se ejecuta el job (`runs-on: self-hosted` en vez de `ubuntu-latest`). Se registró como agente de usuario (`launchd`, `~/Library/LaunchAgents`, sin `sudo`) en vez de un daemon de sistema — arranca con la sesión del usuario, no requiere privilegios de administrador.
- **Consecuencia aceptada explícitamente**: el CI de este repo solo corre mientras esta Mac esté encendida y despierta. Es un trade-off consciente, no un descuido — la alternativa (SonarCloud) habría significado pagar por un repo privado o cambiar el stack de SonarQube ya establecido para todos los proyectos futuros.
- **`sonarqube-quality-gate-action` (SonarSource, oficial) para el fallo real del pipeline**: la tarea `sonar` de Gradle solo ENVÍA el análisis a SonarQube — no espera ni falla según el resultado del Quality Gate por sí misma. Esta acción lee `build/sonar/report-task.txt` (que la tarea `sonar` genera) y consulta la API de SonarQube hasta que el análisis y el Quality Gate terminan, fallando el job si el gate queda en rojo. Fijada a un tag de release (`v1.2.1`), no a `@master`, por higiene de cadena de suministro (una rama flotante de un tercero podría cambiar sin aviso).
- **Runner con Docker real disponible**: al correr en la misma Mac que ya tiene OrbStack, los tests con Testcontainers (Postgres/Redis) funcionan en CI exactamente igual que en local — sin necesitar Docker-in-Docker ni configuración adicional, otra ventaja incidental del self-hosted runner sobre uno efímero en la nube.
- **Notificación a Telegram siempre, éxito o fallo** (`if: always()`): reusa `~/dev-infra/scripts/notify.sh`, que además ya sabe leer sus propias credenciales de `~/dev-infra/.env` directamente (por eso pasar `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID` como secretos de GitHub Actions es defensivo/redundante aquí, no estrictamente necesario para que `notify.sh` funcione en este runner — se dejaron configurados igual por si un paso futuro necesita llamarlos directamente).
- **`SONAR_TOKEN` no existía todavía**: `~/dev-infra/.env` tenía `SONAR_HOST_URL` pero `SONAR_TOKEN` vacío (nunca se había generado un token real desde que se levantó la infraestructura). Se generó uno nuevo vía la API de SonarQube (`/api/user_tokens/generate`, credenciales admin por defecto de primer arranque) y se persistió tanto en `~/dev-infra/.env` (para reuso en futuros proyectos) como en el secreto de GitHub Actions de este repo.
- **Verificado en vivo, no solo documentado**: se corrió `./gradlew build sonar` localmente antes de empujar (Quality Gate confirmado en `OK` vía la API de SonarQube), y se observó la ejecución real del workflow en GitHub Actions sobre el runner recién registrado antes de cerrar el ticket — ver la sección "Hecho" del ticket `010` para el resultado exacto.

## Post-backlog: popup nativo de usuario/contraseña y rediseño visual (2026-08-21)

Reportado por el Product Owner probando la UI del ticket 009 en un navegador real: al cargar cualquier página, y al enviar el formulario de método de 2FA, aparecía el diálogo nativo de usuario/contraseña del navegador.

- **Causa raíz (dos bugs relacionados, mismo origen)**: `SecurityConfig` traía `.httpBasic(basic -> {})` desde el ticket 002 ("placeholder" según su propio Javadoc, nunca revisado). Cualquier 401 que pasara por ahí incluía el header `WWW-Authenticate: Basic`, y **eso** es lo que hace que un navegador real (no un cliente API) muestre su propio diálogo nativo — no algo que la app pueda estilizar ni suprimir del lado del cliente.
  1. `/favicon.ico`, pedido por el navegador en cada carga de página, nunca estuvo en `permitAll` → 401 directo con el challenge, en cada carga.
  2. Cualquier excepción no manejada en un endpoint que SÍ está en `permitAll` (ej. falta el header `X-Client-Id`, como en la selección de método de 2FA) hace que el contenedor reenvíe internamente a `/error` — una petición NUEVA por el mismo filtro — y como `/error` tampoco estaba en `permitAll`, ese reenvío disparaba el mismo challenge, enmascarando el error real (400) detrás de un popup de login. **Esta es la misma clase de bug que ya se había visto en el ticket 007** (BouncyCastle enmascarado como 401) — esa vez se arregló la causa puntual sin arreglar el mecanismo general de enmascaramiento; esta vez se arregla de raíz.
- **Fix**: se quitó `.httpBasic()` por completo (esta app no tiene ningún flujo real de HTTP Basic — no hacía nada útil, solo agregaba el challenge). En su lugar, un `AuthenticationEntryPoint` propio devuelve 401 sin el header `WWW-Authenticate`, con el mismo formato JSON (`ErrorResponse`) que el resto de la API. `/favicon.ico` y `/error` se agregaron a `permitAll`. `MissingRequestHeaderException` (la causa concreta del caso de 2FA) ahora se traduce en `GlobalExceptionHandler` a un `400 validation_error` consistente, en vez de caer al formato de error por defecto de Spring Boot.
- **Verificado con tests de regresión** (`AuthControllerTest`, `IdentityProviderControllerTest`): un header faltante da 400 sin `WWW-Authenticate`; un endpoint deliberadamente protegido (`/identity-providers`) sigue devolviendo 401 fail-closed, pero también sin el header. 183/183 tests en verde.
- **Descubrimiento de estilo (Spring Boot 4.1 / Jackson 3)**: `ObjectMapper` ya no vive en `com.fasterxml.jackson.databind` — Jackson 3 lo movió a `tools.jackson.databind`. Encontrado por el compilador, no asumido.
- **Rediseño visual** (`static/css/app.css`): el Product Owner pidió una estética más moderna y minimalista. Se rehizo la hoja de estilos completa — tarjeta central con sombra suave sobre fondo neutro, header sin barra de color sólida (un punto de acento + el nombre del tenant), inputs con anillo de foco suave (`box-shadow` + `outline`, no solo uno u otro, para no perder accesibilidad), botones con estados hover/active vía `color-mix()` sobre `--primary-color` — así el hover se deriva automáticamente del color de cada tenant, sin necesitar una segunda variable configurable. **Sin fuente web externa (Google Fonts, etc.) a propósito**: depender de un CDN de terceros solo para renderizar texto es un trade-off real de privacidad/disponibilidad que la mayoría de proveedores de identidad evitan; la pila de fuentes del sistema ya se ve nativa y moderna en cada plataforma. Cero cambios de HTML/JS — todo el rediseño es CSS puro, así que ningún test de `UiPagesControllerTest` necesitó cambios.

## Ticket 011: RBAC — roles de plataforma y de cliente para el panel de administración

Primer ticket de la épica "panel de administración de clientes" (documento de definición en `docs/definiciones/panel-administracion-clientes.md`, fase de discovery corrida con el Product Owner el 2026-08-21).

- **Columna `role`** en `app_user` (`NONE` | `TENANT_ADMIN` | `PLATFORM_ADMIN`, `TEXT` + `CHECK`, default `NONE`) — mismo patrón que `two_factor_method` (ticket 005), no un tipo `ENUM` nativo de Postgres. Todo usuario existente (todos regulares, ninguno admin del panel) queda sin cambios de comportamiento.
- **`AdminAccessPolicy`** (paquete `security`): lógica de decisión pura, deliberadamente separada de la capa HTTP — responde "¿puede este usuario administrar este tenant?" sin depender de un contexto de servlet. El guard real que la conecta al pipeline de requests es el ticket 012, no este.
- Compara tenants por `id`, no por igualdad de objeto — `Tenant` no sobreescribe `equals`, y dos referencias pueden representar la misma fila persistida.
- Tests con filas reales persistidas (mismo patrón que `TenantIsolationTest`, ticket 008) en vez de objetos armados a mano — así el round-trip de la columna nueva por Flyway/JPA también queda probado, no solo la lógica en memoria.
- 187/187 tests en verde (183 previos + 4 nuevos de `AdminAccessPolicyTest`).
- **Bug de infra preexistente encontrado al intentar mergear este ticket, no algo que este ticket haya causado**: el Quality Gate de SonarQube exige ≥80% de cobertura en código nuevo (`new_coverage`), pero desde el ticket 010 nunca se generaba ni enviaba ningún reporte de cobertura — el plugin `jacoco` nunca se aplicó en `backend/build.gradle`. El gate fallaba en 0% para cualquier PR con código nuevo, sin importar qué tan bien probado estuviera. Arreglado aquí (plugin `jacoco` + `jacocoTestReport` finalizando `test` + `sonar.coverage.jacoco.xmlReportPaths` apuntando al XML) — verificado localmente que el reporte se genera y `AdminAccessPolicy` queda con 100% de cobertura de líneas y ramas.

## Ticket 012: Auth del panel (dogfooding) + guard de rol

Segundo ticket de la épica del panel de administración. Depende del ticket 011 (RBAC).

- **Claims `role`/`tenant_id` en el access token**: `AdminClaimsCustomizer` (`oauth2TokenCustomizer`), wireado en `TokenGeneratorConfig.jwtGenerator(...)`.
- **Hallazgo real vía bytecode, no asumido**: `JwtGenerator.generate(...)` construye internamente un `JwtEncodingContext` propio (`JwtEncodingContext.with(...)`) que **solo copia campos reconocidos** del `OAuth2TokenContext` original (`principal`, `registeredClient`, `authorizedScopes`, `tokenType`, `authorizationGrantType`, y condicionalmente `authorization`/`authorizationGrant`) — un `.put(Object, Object)` genérico en el context original **se pierde**, nunca llega al customizer. Confirmado decompilando el bytecode real del método (no en la documentación). Fix: `DirectTokenService` pasa el `User` vía `.authorizationGrant(new UsernamePasswordAuthenticationToken(user, null, List.of()))` — uno de los pocos campos que sí sobrevive — dejando `principal` intacto (solo el id como string) para no afectar el claim `sub`.
- **`AdminRoleAuthoritiesConverter`**: mapea el claim `role` a autoridad Spring (`ROLE_TENANT_ADMIN`/`ROLE_PLATFORM_ADMIN`); `NONE` o ausente → sin autoridades, nunca fail-open.
- **Hallazgo real, no causado por este ticket**: `SecurityConfig` nunca tuvo `.oauth2ResourceServer(...)` conectado — el `JwtDecoder` ya existía como bean (`AuthorizationServerConfig`, ticket 007) pero nada en la cadena de filtros principal lo usaba para autenticar un Bearer token real. El "primer endpoint que requiere autenticación" (`IdentityProviderController`, ticket 006) solo se probó con `@WithMockUser` — nunca hubo forma real de autenticarse contra él en producción hasta este ticket. Conectado ahora junto con la regla `/api/v1/admin/**` → `hasAnyRole("TENANT_ADMIN", "PLATFORM_ADMIN")`, evaluada antes del `anyRequest().authenticated()` genérico. Sin endpoints admin reales todavía (llegan en el ticket 013+), así que esa regla de ruta se prueba genéricamente aquí, no contra un admin endpoint real.
- **Regresión real encontrada y arreglada**: conectar `.oauth2ResourceServer(...)` rompió los 9 tests `@WebMvcTest` existentes que importan `SecurityConfig` (`NoSuchBeanDefinitionException` — sin `JwtDecoder` en el contexto slice). Arreglado agregando `@MockitoBean private JwtDecoder jwtDecoder;` a cada uno (nunca stubbeado, solo satisface la inyección) — patrón idiomático de Spring para slice tests, no se importó `AuthorizationServerConfig` completo (traería su propia cadena de filtros).
- **Prueba real de punta a punta** (`AdminRoleGateIntegrationTest`, `@SpringBootTest` con Testcontainers real, no mocks): registro real → login real → token real firmado por el `JwtGenerator` realmente configurado → request Bearer real contra `/api/v1/identity-providers` (endpoint que ya existía) → 200. Sin token → 401. Claims `role`/`tenant_id` del token real verificados contra lo que se otorgó. Datos únicos por test (UUID en `client_id`) porque un `@SpringBootTest` con HTTP real no comparte transacción con el test — se descubrió con un `DataIntegrityViolationException` real, no anticipado de antemano.
- 196/196 tests en verde (187 previos + 9 nuevos: 4 de `AdminRoleAuthoritiesConverterTest`, 2 de `DirectTokenServiceTest`, 3 de `AdminRoleGateIntegrationTest`).

## Ticket 017: cifrado por sobres para secretos de clientes (Vault Transit)

Tercer ticket de la épica del panel de administración. Independiente de 011/012/013.

- **Vault local instalado en `~/dev-infra`** (junto a SonarQube) — backend de archivo persistente, no `vault server -dev` (dev mode pierde la master key en cada reinicio, lo que volvería indescifrables las data-keys ya envueltas). Requiere unseal manual tras cada reinicio (`~/dev-infra/scripts/vault-unseal.sh`).
- **Bug real encontrado en vivo**: la imagen oficial de `hashicorp/vault` ya inyecta `-config=/vault/config` (todo el directorio) cuando el primer argumento del comando es `server` — pasar `-config=/vault/config/config.hcl` explícito también duplicaba el listener tcp y crasheaba con "address already in use". Arreglado quitando el `-config` explícito del `docker-compose.yml`.
- **Segundo bug real**: `vault operator init` falló la primera vez con "permission denied" al escribir en `/vault/data/core` — el volumen nombrado se creó con ownership root, pero el proceso vault corre como usuario `vault` no-root. Arreglado con `chown -R vault:vault /vault/data`.
- **`VaultTransitEncryptor`**: llamada HTTP delgada (mismo patrón que `ResendEmailSender`, no la librería completa `spring-vault-core` — evita riesgo de incompatibilidad de versión con este Spring Boot 4.1 bleeding-edge) contra el motor Transit de Vault — envuelve/desenvuelve la data-key de un tenant, nunca ve el secreto real.
- **`TenantSecretEncryptor`**: AES-256-GCM local (misma forma que `SecretEncryptor`, deliberadamente no generalizado — ver su Javadoc) usando la data-key desenvuelta. Cada tenant tiene su propia data-key (`Tenant.wrappedDataKey`, columna nueva, nullable — se genera perezosamente en el primer secreto que se configure, sin necesidad de backfill).
- **`TenantIdentityProviderService`** (ticket 006) migrado de `SecretEncryptor` (clave única) a `TenantSecretEncryptor` (por tenant) — si una data-key envuelta se filtra, el impacto queda acotado a un tenant, no a todos.
- **Verificado que no hacía falta migrar datos reales**: se consultó la base de datos local real (`auth-core-mc-postgres-1`, no la efímera de Testcontainers) — `tenant_identity_provider` tiene 0 filas. Las credenciales reales de Google/Meta obtenidas en el ticket 006 quedaron en `backend/.env` pero nunca se guardaron vía la API real. No hay nada que migrar todavía.
- Tests con Vault real vía Testcontainers (`testcontainers-vault`, mismo patrón hermético que Postgres) — no apuntan al Vault compartido de dev-infra, que podría estar sellado.
- 201/201 tests en verde (196 previos + 5 nuevos: 3 de `TenantSecretEncryptorTest`, 2 de `TenantIdentityProviderServiceTest`).
- Proveedor de KMS confirmado con el Product Owner: HashiCorp Vault self-hosted (pregunta abierta heredada de la definición, ya resuelta).

## Ticket 015: registro de eventos de login

Cuarto ticket de la épica del panel de administración. Independiente de los demás — instrumenta `AuthController.login()` directamente.

- **`LoginEvent`** (tabla nueva, migración V5, con índice `(tenant_id, occurred_at)` pensando en las consultas por rango de fechas del ticket 016) — `user` nullable a propósito: un login fallido con identificador desconocido nunca resuelve a un `User` real, y sigue siendo un evento real que contar.
- **`LoginEventRecorder`**: deliberadamente no-bloqueante — un fallo al guardar el evento nunca debe romper un login real. **Primer uso de un logger en este codebase** (todo lo demás falla explícito a propósito, ver `ResendEmailSender`, pero esa filosofía no aplica a un rastro de auditoría cuyo punto es no estar en el camino crítico).
- Instrumentado en `AuthController.login()` (no dentro de `AuthenticationService`, para no acoplar la lógica de auth con el registro de auditoría) — mide latencia real con `System.currentTimeMillis()` alrededor de la llamada de autenticación.
- Proveedor siempre `"PASSWORD"` por ahora — el flujo real de login social (redirect/callback) sigue pospuesto desde el ticket 006, así que no hay otro proveedor que instrumentar todavía. `TooManyAttemptsException` (rate limiting) deliberadamente no se registra aparte — ya queda reflejado indirectamente en los `FAILURE` que dispararon el límite.
- Prueba de integración real (`LoginEventRecordingIntegrationTest`, `@SpringBootTest` con Testcontainers): un login real exitoso y uno fallido de verdad insertan una fila real en `login_event` — no un mock verificando que el controller llamó al recorder.
- 206/206 tests en verde (201 previos + 5 nuevos).

## Ticket 013: gestión de tenants (alta, edición, baja)

Quinto ticket de la épica del panel de administración. Depende de 011 (roles) y 012 (guard de rol) — es el primer conjunto de endpoints reales bajo `/api/v1/admin/**`.

- **Sin campo `slug` separado**: `Tenant.name` en sí gana una restricción `UNIQUE` real (migración V6) y pasa a ser el identificador estable e inmutable — `update()` cubre solo `appName`/`primaryColor`/los TTLs, nunca `name`. Decisión deliberada para no romper el constructor de `Tenant` (ya con 8 parámetros posicionales), usado directamente en decenas de tests preexistentes de los tickets 001–017.
- **Regresión real, encontrada y arreglada proactivamente antes de correr los tests** (no por un fallo inesperado): agregar `UNIQUE` a `name` rompía 2 tests de integración preexistentes (`AdminRoleGateIntegrationTest`, `LoginEventRecordingIntegrationTest`) que reutilizaban un nombre de tenant fijo entre varios métodos `@Test` de la misma clase `@SpringBootTest` (sin rollback automático entre tests, a diferencia de `@DataJpaTest`). Arreglado agregando `+ UUID.randomUUID()` al nombre en ambos, antes de ejecutar nada.
- **Regresión real, encontrada por un test propio que falló**: `TenantPurgeServiceTest` tenía dos tenants con el mismo nombre hardcodeado (`"Acme"`) en el mismo test, violando la misma restricción `UNIQUE` recién agregada (`DataIntegrityViolationException`). Arreglado haciendo que el helper `deactivatedTenant(int daysAgo)` genere un nombre único (`"Acme-" + UUID.randomUUID()`) en cada llamada.
- **Baja = desactivación reversible, no borrado**: `Tenant.deactivate()`/`reactivate()`, idempotente. `ClientContextResolver.resolveClient(...)` — el único punto central usado por login/registro/proveedores de identidad/etc. — rechaza con `TenantDeactivatedException` (403) cualquier cliente cuyo tenant esté desactivado. Elegido deliberadamente como el único choke-point en vez de repetir el chequeo en cada endpoint.
- **Autorización de grano fino sin consultar la base de datos**: `AdminTenantService` reutiliza `AdminAccessPolicy.canAccessTenant(UserRole, UUID, UUID)` (construido en los tickets 011/012) leyendo `role`/`tenant_id` directamente de los claims del JWT — un `PLATFORM_ADMIN` puede operar cualquier tenant; un `TENANT_ADMIN` solo el suyo. La regla gruesa de ruta (`/api/v1/admin/**` requiere alguno de los dos roles) sigue viviendo en `SecurityConfig` (ticket 012); esta es la capa fina "¿cuál tenant específico?".
- **`TenantPurgeService`**: job diario (`@Scheduled(cron = "0 0 3 * * *")`, requirió agregar `@EnableScheduling` a `AuthCoreMcApplication`) que purga tenants desactivados hace ≥90 días — ventana de retención acordada con el Product Owner en la fase de definición. Borra explícitamente en orden de dependencia FK (refresh_token vía sus usuarios → login_event → tenant_identity_provider → identity_client → app_user → tenant) en vez de depender de `ON DELETE CASCADE` a nivel de base de datos (no configurado en ningún otro lado de este esquema) — decisión deliberada por auditabilidad: cada borrado queda como una operación explícita y rastreable, no un efecto secundario implícito del motor de base de datos. `purge(Tenant)` se niega (`IllegalStateException`) si el tenant no está desactivado o no ha cumplido la ventana completa de 90 días.
- **Prueba real de purga** (`TenantPurgeServiceTest`, `@DataJpaTest` con Postgres real): un tenant con una fila real de cada tipo dependiente (usuario, refresh token, cliente de identidad, proveedor configurado, evento de login) — todas desaparecen tras `purge()`, el tenant también. Casos de rechazo (menos de 90 días, tenant aún activo) probados por separado.
- **Cierre de un hueco que el propio ticket 012 dejó anotado como pendiente**: en 012, la regla `/api/v1/admin/**` solo se probó genéricamente porque todavía no existía ningún endpoint admin real. Este ticket sí lo tiene, así que se agregó `AdminTenantEndToEndTest` (`@SpringBootTest`, sin mocks): registro real → login real → JWT real → request Bearer real contra `/api/v1/admin/tenants` — `PLATFORM_ADMIN` puede crear; un usuario sin rol admin recibe 403 real; un `TENANT_ADMIN` puede leer su propio tenant pero recibe 403 real al intentar leer otro.
- **Bug real de correctitud, encontrado por el Quality Gate de SonarQube en el PR, no por un test** (`java:S2229` BLOCKER + `java:S6809` CRITICAL): `purgeEligibleTenants()` llamaba a `purge(tenant)` con auto-invocación (`this.purge(...)`) dentro de la misma clase. Spring AOP solo intercepta llamadas que llegan a través del proxy del bean — una auto-invocación así se salta silenciosamente el `@Transactional` de `purge(...)`, dejando el borrado en cascada sin límite de transacción real (un fallo a mitad de camino no revertiría nada). Arreglado separando el punto de entrada `@Scheduled` en un bean propio, `TenantPurgeScheduler`, que llama a `TenantPurgeService.purge(...)` como una dependencia inyectada — así la llamada sí pasa por el proxy real.
- 223/223 tests en verde (206 previos + 17 nuevos: 1 de `ClientContextResolverTest`, 9 de `AdminTenantServiceTest`, 3 de `TenantPurgeServiceTest`, 1 de `TenantPurgeSchedulerTest`, 3 de `AdminTenantEndToEndTest`).

## Ticket 014: UI de configuración de proveedores de login por cliente

Sexto ticket de la épica del panel de administración. Depende de 011/012 (RBAC + guard) y usa el cifrado de sobres del ticket 017 de forma transparente (sin cambios a esa capa).

- **Sin tocar `TenantIdentityProviderService`** (tal como pedía el Objetivo del ticket): se agregó `AdminIdentityProviderController`, un controller nuevo y delgado bajo `/api/v1/admin/identity-providers` que delega directo al servicio ya existente del ticket 006/017. La única diferencia real con `IdentityProviderController` (el endpoint original, usado por la propia app cliente vía header `X-Client-Id`, sin auth) es cómo se resuelve el caller/tenant: aquí es un JWT de admin real, no un header. Al vivir bajo `/api/v1/admin/**`, la regla de rol del `SecurityConfig` (ticket 012) ya lo protege — **cero cambios a `SecurityConfig`** para este ticket.
- **Alcance deliberado: solo el tenant propio del admin.** El endpoint siempre opera sobre el `tenant_id` del propio JWT (mismo claim que `AdminClaimsCustomizer` ya estampa) — un `platform_admin` que quisiera configurar un tenant que no es el suyo necesitaría un selector de tenant, fuera de alcance aquí (el Objetivo del ticket dice "de su cliente", no "de cualquier cliente"). Si se necesita a futuro, es una extensión aditiva sobre este mismo controller.
- **Primera UI real del panel de administración** — antes de este ticket no existía ninguna página de panel autenticada. Reutiliza el login existente (`/ui/login`, ticket 009) como "login del panel" (mismo dogfooding del ticket 012: un admin simplemente inicia sesión con sus propias credenciales) — sin página de login separada. Requirió que `api.js`/`login.html` empezaran a **guardar el access token real** en `sessionStorage` (antes solo se guardaba `userId`/email — el token se descartaba por completo tras el login) para que la nueva página pudiera adjuntarlo como `Authorization: Bearer` en sus llamadas (`AuthCoreUi.callAdmin(...)`, nueva función). Cambio aditivo: ninguna página existente leía el token antes, así que nada cambia para ellas.
- **Apple no aparece como opción** en la UI (solo tarjetas de Google/Facebook) — consistente con que el servicio ya la rechaza (`UnsupportedProviderException`, ticket 006); se probó también a través de este nuevo endpoint admin, no solo del original.
- **El secreto nunca se precarga**: `IdentityProviderView` (ya existente) nunca serializa el secreto, así que la UI físicamente no puede mostrarlo — el campo de secreto en el formulario siempre es obligatorio y vacío, nunca lee un valor previo.
- Prueba real de punta a punta (`AdminIdentityProviderEndToEndTest`, sin mocks) — necesitó su propio contenedor Vault real (Testcontainers, mismo patrón hermético que `TenantSecretEncryptorTest` pero wireado vía `@DynamicPropertySource` porque aquí los beans de cifrado sí los levanta Spring) porque `configure()` pasa por el cifrado de sobres real. Cubre: configurar→listar→desactivar un proveedor real, 403 para un usuario sin rol admin, 400 al intentar configurar Apple.
- Siguiendo el mismo patrón que el ticket 013 estableció para controllers admin delgados: sin `@WebMvcTest` dedicado para el controller (la lógica de negocio ya está probada a fondo en `TenantIdentityProviderServiceTest`), la prueba end-to-end es la que prueba el wiring real.
- 227/227 tests en verde (223 previos + 4 nuevos: 3 de `AdminIdentityProviderEndToEndTest`, 1 de `UiPagesControllerTest`).

## Ticket 016: endpoints y UI de métricas de uso por cliente

Séptimo ticket de la épica del panel de administración. Depende del ticket 015 (`login_event`) y de 011/012 (RBAC + guard).

- **`GET /api/v1/admin/tenants/{id}/metrics?from=&to=`** vive en el mismo `AdminTenantController` del ticket 013 (misma URL base, mismos helpers de extracción de rol/tenant del JWT) — no un controller nuevo. Mismo patrón de autorización de grano fino que `get()`/`update()`: `platform_admin` cualquier tenant, `tenant_admin` solo el suyo (`AdminAccessPolicy`, sin consulta a BD para autorizar).
- **Agregación en Java, no SQL**: `AdminMetricsService` trae los `login_event` del rango con una única consulta derivada (`findByTenantAndOccurredAtBetween`) y agrega en memoria (conteos por resultado/proveedor, usuarios activos únicos, latencia promedio) — consistente con que ningún repositorio de este codebase usa `@Query` todavía, y con la decisión de "volumen bajo" ya tomada en la fase de definición (misma filosofía que `TenantPurgeService`, ticket 013).
- **Usuarios "registrados" vs "activos"**: registrados = todos los `User` del tenant (`UserRepository.findByTenant`, ya existente del ticket 013), sin acotar por rango de fechas (el registro no se modela en `login_event`). Activos = usuarios distintos con al menos un login `SUCCESS` dentro del rango.
- **Rango por defecto de 30 días** si no se pasan `from`/`to` — para que la primera visita a la página muestre algo sin obligar a elegir fechas. `from > to` responde 400 (reutiliza el `@ExceptionHandler(IllegalArgumentException.class)` genérico ya existente, sin exception nueva).
- **Tenant sin actividad = 200 con todo en cero, nunca un error** — probado explícitamente, tanto a nivel de servicio como end-to-end.
- **Primer uso de `AuthCoreUi.currentTenantId()`**: decodifica el `tenant_id` del propio JWT guardado en `sessionStorage` (base64url, sin verificar firma — es solo una conveniencia de UI para precargar el campo de tenant, nunca decide autorización) para que un `tenant_admin` vea sus propias métricas sin escribir nada; un `platform_admin` puede editar el campo para consultar cualquier tenant. Sin selector de lista (no existe todavía un endpoint "listar todos los tenants") — se escribe el ID directamente.
- Prueba real de punta a punta (`AdminMetricsEndToEndTest`, sin mocks): logins reales producen filas reales de `login_event`, la consulta HTTP real devuelve los conteos correctos; 403 real para `tenant_admin` sobre un tenant ajeno; 200 con ceros para un tenant sin actividad; 400 para un rango invertido.
- 238/238 tests en verde (227 previos + 11 nuevos: 6 de `AdminMetricsServiceTest`, 4 de `AdminMetricsEndToEndTest`, 1 de `UiPagesControllerTest`).

## Ticket 018: mecanismo de break-glass para acceso de emergencia

Octavo y último ticket de la épica del panel de administración. Independiente de los demás — nace de un riesgo explícito ("dependencia circular") anotado en la fase de definición: si el panel de administración depende del mismo OAuth2/JWT que puede estar fallando durante un incidente, el equipo no tiene forma de intervenir.

- **Preguntas de diseño abiertas del ticket, resueltas con el Product Owner antes de implementar** (no asumidas): segundo factor = allowlist de IP **y** TOTP **y** secreto compartido (los tres, no una alternativa) — defensa en profundidad para una puerta de alto privilegio. Alcance v1 = diagnóstico + capacidad de desactivar un tenant (no una intervención más amplia, que queda para un ticket futuro con su propio VoBo).
- **Independencia real de `AuthController`/OAuth2**: `BreakGlassController` vive bajo `/api/v1/breakglass/**`, agregado a la lista `permitAll()` de `SecurityConfig` — deliberadamente NO pasa por `.oauth2ResourceServer(...)` ni por ningún filtro de rol. La autorización entera vive dentro de `BreakGlassService` (secreto + TOTP + IP), nunca toca `AuthenticationService`/`DirectTokenService`/`JwtDecoder`. Probado literalmente: `BreakGlassEndToEndTest` nunca envía un header `Authorization` en ningún test.
- **Nunca falla abierto**: si cualquiera de los tres factores no está configurado (variables de entorno vacías), todo intento se rechaza — nunca se asume "sin configurar = permitir".
- **Comparación de secreto en tiempo constante** (`MessageDigest.isEqual`, endurecido así desde Java 6u17) — evita un side-channel de timing sobre el secreto compartido.
- **TOTP propio, deliberadamente sin protección de replay vía Redis** (a diferencia de `TotpService`, ticket 005): depender de Redis aquí reintroduciría exactamente el tipo de dependencia circular que este ticket existe para evitar. Riesgo residual (un código capturado es reutilizable el resto de su ventana de ~90s) aceptado explícitamente, no en silencio.
- **Respuesta HTTP deliberadamente genérica** (`BreakGlassAuthenticationException`, siempre 401 sin decir qué factor falló) — para no darle a un atacante con factores parciales un oráculo de qué le falta. La razón específica (`not configured` / `IP not allowed` / `invalid secret` / `invalid TOTP code`) solo se guarda en la auditoría, para el equipo que opera esta puerta.
- **Auditoría fuerte de cada llamada, éxito o fallo** (`break_glass_audit_event`, migración V7): guardada en BD (best-effort, sin romper el flujo si falla — mismo patrón que `LoginEventRecorder`, ticket 015) **y** logueada siempre vía SLF4J como respaldo — la escritura a BD nunca es el único rastro. `target_tenant_id` deliberadamente sin FK a `tenant` para que el registro sobreviva a una purga futura de ese tenant (ticket 013).
- **Diagnóstico resiliente a un fallo de BD**: si la propia consulta de conteos falla, el endpoint responde 200 con `databaseHealthy: false` en vez de un 500 — el punto entero de este endpoint es funcionar cuando algo más está roto.
- **Limitación conocida, documentada, no relevante hoy pero a revisar antes de poner un reverse proxy/load balancer delante de esta app**: la allowlist de IP compara contra `HttpServletRequest.getRemoteAddr()` — el peer TCP directo. Detrás de un proxy, eso sería la IP del proxy, no la del llamador real, volviendo la allowlist inútil o siempre-bloqueante según el caso. `dev-infra` hoy es conexión directa, así que es correcto tal cual está; quien agregue un reverse proxy en el futuro debe agregar manejo de `X-Forwarded-For` confiable aquí también, no asumir que sigue funcionando.
- Tests: `BreakGlassServiceTest` (unitario, cada factor rechazado por separado, fallo de BD tolerado, desactivación real) + `BreakGlassEndToEndTest` (HTTP real, Postgres real, **cero uso de JWT/login en todo el archivo**).
- 250/250 tests en verde (238 previos + 12 nuevos: 8 de `BreakGlassServiceTest`, 4 de `BreakGlassEndToEndTest`).

Con este ticket se completa la épica "panel de administración de clientes" (011–018).

## Lecciones del ticket 001 (por qué los tests están configurados así)

- **`@DataJpaTest` no usa Flyway por defecto**: genera el esquema directamente desde las anotaciones `@Entity`, lo cual habría dejado los tests corriendo contra un esquema paralelo que nunca valida que `V1__init.sql` sea correcto. Se forzó `spring.jpa.hibernate.ddl-auto=validate` en `backend/src/test/resources/application.properties` para que Hibernate solo *valide* contra lo que Flyway ya creó, nunca lo genere.
- **La traducción de excepciones de Spring solo aplica a llamadas a través del proxy `@Repository`**: si el test llama `entityManager.flush()` directamente (para forzar el INSERT diferido de Hibernate), la excepción que sale es la nativa de Hibernate (`org.hibernate.exception.ConstraintViolationException`), no la traducida de Spring (`org.springframework.dao.DataIntegrityViolationException`). Ambas ocurren en este proyecto según de dónde se dispare el flush — ver los tests de `UserRepositoryTest` para el patrón exacto.
- **OrbStack se suspende solo por inactividad** y detiene todos los contenedores (Testcontainers de los tests, SonarQube). Cualquier `./gradlew test` puede fallar con `DockerClientProviderStrategy`/`IllegalStateException` simplemente porque OrbStack estaba dormido — solución: `open -a OrbStack` y esperar unos segundos antes de reintentar.

## Nota de tooling (2026-08-21): hooks de QA automática arreglados

Cada sección de la épica 011–018 arriba menciona "mismo bug de permisos del hook agent" en los comentarios de QA de cada PR — los dos hooks `PostToolUse` (`gh pr create`/`gh pr merge`) que debían revisar automáticamente cada PR nunca funcionaron durante toda la épica: eran de tipo `agent` (spawneaban un subagente), y el subagente ni heredaba el modo de permisos "don't ask" de la sesión (Bash quedaba denegado) ni el filtro `if` de `settings.json` los limitaba correctamente a `gh pr create`/`gh pr merge` — se disparaban en cada comando Bash de la sesión. Root-cause confirmado cruzando IDs de tool-call en el transcript de la sesión, no solo por el mensaje de error superficial.

Arreglados convirtiéndolos a hooks `type: command` (`~/.claude/hooks/qa-review-pr-create.sh` / `qa-review-pr-merge.sh`) — mismo patrón que `ci-status-gate.sh`, que sí funcionaba: corren en el contexto de shell de la sesión (sin problema de herencia de permisos) y se auto-filtran leyendo `tool_input.command` directamente en vez de confiar solo en el `if` de `settings.json`. A cambio de dejar de tener revisión semántica por LLM, ahora corren de verdad en cada PR: chequeos determinísticos de secretos hardcodeados, hashing débil, PII en logs, accesibilidad básica y migraciones Flyway modificadas — complementarios al review manual, no un reemplazo.

## Ticket 019: listado de todos los clientes (solo admin global)

Encontrado al probar en vivo la épica 011–018: no había forma de descubrir qué tenants existen sin conocer su ID de antemano (anotado como fuera de alcance del ticket 013).

- **`GET /api/v1/admin/tenants`** en el mismo `AdminTenantController` — `AdminTenantService.list(actorRole)` exige `PLATFORM_ADMIN` explícitamente, **no** reutiliza `AdminAccessPolicy.canAccessTenant` (esa política responde "¿puede este actor llegar a UN tenant específico?"; listar TODOS es una pregunta de autorización distinta y más amplia — delegarla a esa política habría sido incorrecto, no solo una decisión de estilo).
- **UI en `/ui/admin/tenants`**: tabla con nombre/app/estado/fecha, enlace "Ver métricas" por fila que navega a `/ui/admin/metrics?tenant=<id>` — la página de métricas se extendió para leer ese query param y priorizarlo sobre el tenant propio del caller (ticket 019), ya que el punto de seguir ese enlace es ver OTRO tenant.
- **Decisión deliberada de alcance**: NO se agregó un enlace equivalente a "proveedores de login" — esa página es intencionalmente solo-tenant-propio desde el ticket 014 (lee el tenant directo del JWT del caller, sin aceptar uno arbitrario). Extenderla a cualquier tenant es un cambio de alcance real de una decisión ya tomada, no algo que se pidió aquí — se deja fuera explícitamente en vez de expandirlo en silencio.
- Sin paginación (volumen bajo, decisión ya establecida para este proyecto).
- Probado end-to-end real y también en vivo en el navegador: `platform_admin` ve la lista completa de tenants reales (incluyendo uno desactivado, mostrado como tal); `tenant_admin` recibe 403 real con mensaje claro, tanto vía API como en la UI.
- 255/255 tests en verde (250 previos + 5 nuevos).

## Ticket 020: sistema visual + layout compartido del panel de administración

Primer ticket del rediseño de UI definido en `docs/definiciones/rediseno-ui-completo.md` (HU-1, HU-2). El panel admin gana identidad visual propia y navegación real entre sus 3 pantallas — hasta este ticket, cada página era una isla sin nav ni sistema visual propio.

- **`static/css/admin.css` (nuevo)**: paleta "Slate + Índigo" confirmada con el Product Owner (elegida entre 3 propuestas mostradas en contexto real, no como swatches). Nunca lee `--primary-color`; `app.css` queda intacto y sigue siendo exclusivo de las páginas de usuario final — cero riesgo de regresión ahí.
- **`templates/fragments/admin-shell.html` (nuevo)**: tres fragmentos Thymeleaf (`topbar`, `sidenav(active)`, `script`) — separados en fragmentos distintos, no uno solo, porque topbar y sidenav viven en niveles distintos del layout flex (`.admin-shell` → `.admin-topbar` + `.admin-body-row` → `.admin-sidenav` + `.admin-content`); un único fragmento wrapper no podía producir esa estructura con `th:replace`.
- **`api.js` gana `logout()` y `currentRole()`** (aditivas, mismo patrón que `currentTenantId()` — se extrajo un `decodeJwtPayload()` compartido para las tres).
- **Bug real encontrado en vivo, no por ningún test automatizado**: el script del shell usaba `if (window.AuthCoreUi)` como guard antes de mostrar "Clientes"/enganchar el logout — pero un `const AuthCoreUi = ...` de nivel superior en un script clásico (no-módulo) **no se convierte en propiedad de `window`**, a diferencia de `var` o una función declarada. El guard era `if (undefined)` siempre, así que ese bloque nunca corría en la vida real — aunque `AuthCoreUi` sí era perfectamente accesible como identificador libre en el mismo scope. Encontrado navegando la app real (el link "Clientes" nunca aparecía para un platform_admin real, pese a que la lógica probada manualmente en consola funcionaba). Arreglado cambiando el guard a `typeof AuthCoreUi !== "undefined"`. Ninguna prueba automatizada de este proyecto ejecuta JS real en un navegador — por diseño (sin toolchain de JS), así que este tipo de bug solo lo encuentra una prueba en vivo, no la suite de tests de Java.
- **"Clientes" se oculta/muestra 100% client-side** (`currentRole()` decodificado del JWT) — no hay sesión de servidor real detrás de estas páginas Thymeleaf (mismo trust-boundary documentado desde el ticket 009), así que el servidor no tiene ningún rol que consultar al renderizar. La aplicación real de la regla sigue siendo el 403 del backend (ticket 019) si alguien navega a `/ui/admin/tenants` directo — esto solo evita mostrar un link que fallaría.
- Las 3 páginas admin existentes migradas al nuevo shell/estilos — de paso se corrigió un bug cosmético ya anotado desde el ticket 014 (el texto "cargando…" de proveedores de login quedaba pegado tras un error de carga, en vez de reflejar la falla).
- Los 3 tests de `UiPagesControllerTest` que antes afirmaban `"Acme App"` en las páginas admin se actualizaron — ahora afirman explícitamente lo contrario (`doesNotContain("Acme App")`), ya que el panel dejó de tematizarse por tenant a propósito.
- Probado en vivo en el navegador con los 3 roles reales: navegación entre secciones con resaltado correcto, "Clientes" visible solo para `platform_admin`, cerrar sesión funcionando de verdad.
- 255/255 tests en verde (sin tests nuevos — el ticket es HTML/CSS/JS de presentación; la cobertura server-side existente ya prueba que las páginas siguen respondiendo 200 con el contenido correcto).

## Ticket 021: alta y edición de tenant desde la UI

Segundo ticket del rediseño de UI. Formularios reales en `/ui/admin/tenants`, sin backend nuevo — reutiliza `POST`/`PUT /api/v1/admin/tenants` ya existentes desde el ticket 013.

- **Decisión resuelta con el Product Owner antes de implementar**: HU-2 (ticket 019, ya cerrada) dice que `tenant_admin` nunca ve la sección "Clientes" en absoluto; HU-5 del documento de rediseño decía que `tenant_admin` debería poder editar su propio tenant desde la UI — una contradicción real entre dos HUs ya aprobadas, no algo resoluble asumiendo. Confirmado: por ahora, edición desde esta UI es efectivamente solo-platform_admin (la única pantalla que la expone requiere ver la lista completa). Un `tenant_admin` sigue pudiendo editar su propio tenant vía API directa (el `PUT` ya lo soporta) — simplemente no tiene pantalla propia todavía. Reabrir la decisión del ticket 019 (mostrarle a un tenant_admin su propio tenant en la lista) queda fuera de este ticket — cambiaría un comportamiento de seguridad ya cerrado y probado, y eso necesita su propio VoBo dedicado, no colarse de paso aquí.
- Formulario de alta con los 5 TTLs precargados con los valores por defecto ya usados en todo el proyecto (900/2 592 000/86 400/3 600/300) — reduce fricción sin ocultar que son editables.
- Edición vía un `<dialog>` HTML real (no un formulario embebido en la fila) — precarga los valores actuales desde los datos ya en memoria de la tabla (sin una llamada `GET` adicional), hace `PUT` al guardar.
- Confirmado en vivo: alta real (aparece en la tabla sin recargar), edición real (cambios reflejados de inmediato), formulario se limpia tras crear.
- Ajuste visual encontrado en vivo: `input[type="color"]` heredaba el padding de un input de texto normal y se veía como una barra plana en vez de un swatch — corregido con una regla específica.
- Sin tests nuevos — sin backend nuevo que probar (los endpoints ya están cubiertos desde el ticket 013); esta es una capa de presentación pura, verificada en vivo.
- 255/255 tests en verde (sin cambio, ningún test se agregó ni se rompió).

## Ticket 022: reactivar tenant + confirmación antes de desactivar

Tercer ticket del rediseño de UI (HU-6, HU-7). `Tenant.reactivate()` existía desde el ticket 013 pero ningún endpoint lo exponía — este ticket agrega el backend faltante y un diálogo de confirmación real (no `confirm()` nativo), compartido por "desactivar" y "reactivar".

- **`POST /api/v1/admin/tenants/{id}/reactivate` (nuevo)**: mismo patrón exacto que `deactivate` — `AdminTenantService.reactivate(actorRole, targetTenantId)`, `platform_admin`-only, delega en `Tenant.reactivate()` ya existente. Sin migración nueva (`deactivated_at` ya era nullable).
- **Prueba end-to-end real** (`AdminTenantEndToEndTest`): tenant creado → registro funciona → `platform_admin` lo desactiva vía el endpoint real → registro pasa a 403 (`tenant_deactivated`, mismo choke-point de `ClientContextResolver` del ticket 013) → se reactiva vía el endpoint real → registro vuelve a funcionar de inmediato. Usa un tenant separado del que autentica al actor, para probar el efecto sobre OTRO tenant, no un artefacto del propio login.
- **Diálogo de confirmación compartido** (`dialog.confirm-dialog`, ya preparado en `admin.css` desde el ticket 020): un solo componente reutilizado por "Desactivar" y "Reactivar", con el mensaje ajustado según la acción pendiente. Cancelar cierra el diálogo sin ninguna llamada al backend (verificado en vivo).
- **UI en `/ui/admin/tenants`**: la columna "Acciones" ahora también muestra "Desactivar" (solo en tenants activos) o "Reactivar" (solo en desactivados) junto a "Editar" — antes de este ticket no existía ningún control de baja/alta en la UI, solo el `DELETE`/nuevo `POST` a nivel API.
- **Bug de layout encontrado en vivo, no por ningún test automatizado**: con dos botones en la misma celda, el texto se recortaba a mitad de palabra ("Desa…", "Reac…") dentro del contenedor de scroll horizontal de la tabla — la celda no se ensanchaba, simplemente cortaba el contenido porque no se estaba usando la barra de scroll interna visiblemente en ese viewport. Corregido apilando los botones verticalmente con un wrapper interno (`.actions-cell-inner`, flex-column) en vez de dársele `display:flex` al propio `<td>` (romper la semántica de tabla del `<td>` desalinea la fila con sus hermanas).
- Probado en vivo: confirmar/cancelar ambas acciones con los mensajes correctos, cambio de estado reflejado sin recargar, botón cambia de "Desactivar" a "Reactivar" tras cada acción.
- 259/259 tests en verde (4 nuevos: 2 unitarios en `AdminTenantServiceTest` — permiso y efecto de `reactivate` —, 2 end-to-end en `AdminTenantEndToEndTest` — permiso real y el ciclo completo desactivar→reactivar).

## Ticket 023: rediseño de las páginas de usuario final

Cuarto y último ticket del rediseño de UI (HU-3). El theming `--primary-color` por tenant y la base visual de `app.css` ya estaban sólidos (rediseñados por feedback del Product Owner antes de este ticket) — la brecha real encontrada al revisar las 7 páginas era otra: **ningún formulario mostraba ningún estado mientras esperaba al backend**, y una tarjeta con más de un `<form>` no tenía ningún espacio visual entre ellos.

- **`AuthCoreUi.withBusy(button, task)` (nuevo, aditivo en `api.js`)**: deshabilita el botón y le agrega `.is-loading` (spinner CSS, texto oculto sin cambiar el ancho del botón) mientras `task` corre, restaurando siempre en un `finally` — se recupera incluso si `task` lanza. `showStatus()` también limpia `is-loading` ahora, para que las dos páginas de confirmación por token (que muestran el spinner apenas cargan, antes de que exista ningún botón) lo limpien gratis en su único llamado a `showStatus()`.
- Aplicado a los 11 puntos de envío async de las 5 páginas con formularios (`register`, `login`, `password-reset/request`, `password-reset/confirm`, y las 7 acciones de `cuenta` — reenviar verificación + 6 formularios de 2FA).
- Las 2 páginas de confirmación por token (`verify-email/confirm`, `change-email/confirm`) llevan la clase `is-loading` ya en el HTML servido (`"Verificando…"`/`"Confirmando…"`) — mismo spinner, sin JS adicional en esas páginas.
- **Bug real de espaciado encontrado en vivo**: `section.card` no tenía `gap`, así que dos `<form>` hermanos dentro de la misma tarjeta (la sección 2FA de `cuenta.html`, con 4 sub-flujos) quedaban pegados sin ningún espacio — el botón de un formulario tocaba la etiqueta del siguiente. Corregido con `display:flex; flex-direction:column; gap` en `section.card`.
- **Jerarquía real, no solo espaciado**: los `<hr/>` que separaban los 3 sub-flujos de 2FA (código por correo/SMS, TOTP, método preferido) se reemplazaron por encabezados `<h4>` reales dentro de un wrapper `.subsection` — nombran cada sub-flujo en vez de solo separarlo visualmente.
- **Estados vacíos**: revisados los 7 templates explícitamente (no asumido) — ninguno tiene contenido tipo lista/colección (a diferencia de la tabla de tenants del panel admin), así que no aplica un estado vacío nuevo; el único caso análogo ya existía antes de este ticket (`cuenta.html` ya mostraba `"(sin correo registrado)"` como fallback).
- **Cero cambios al contrato de `api.js` con el backend** — `withBusy` es una envoltura puramente de UI alrededor de las llamadas ya existentes, ningún endpoint ni payload cambió.
- Probado en vivo: spinner de botón (forzado con una promesa retrasada para verificar el CSS, ya que las respuestas locales son demasiado rápidas para capturarlo con una petición real), spinner de las páginas de confirmación por token, layout de 2FA con los nuevos encabezados, y un error 500 real (falta `RESEND_API_KEY` en este entorno — limitación preexistente, no relacionada) confirmando que el estado de carga también se limpia correctamente en el camino de error.
- 259/259 tests en verde (sin tests nuevos — presentación pura; `UiPagesControllerTest` ya cubre que las 7 páginas siguen respondiendo 200 con el theming correcto).

## Ticket 024: mejora del agente ux-ui-designer + sistema de íconos SVG reutilizable

Primer ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-9, base técnica de HU-4). Primer ticket de este proyecto ejecutado con **delegación real a subagentes** (no solo el hilo principal actuando "con el sombrero puesto" de cada rol) — ver decisión en memoria `elite-dev-org-persona`.

- **`~/.claude/agents/ux-ui-designer.md` actualizado**: nueva sección "Estándar de entrega" que exige por defecto iconografía/ilustraciones reales, SVG inline sin dependencias externas, animaciones con `prefers-reduced-motion`, y últimas mejores prácticas de diseño de producto — ya no es una sugerencia, es parte fija del rol.
- **Hallazgo real de infraestructura de agentes**: los 10 subagentes personalizados en `~/.claude/agents/` (incluido `ux-ui-designer` recién editado) no estaban cargados en el runtime de esta sesión — `Agent` con `subagent_type: "ux-ui-designer"` falló con `Agent type not found`, `ListAgents` reportó cero agentes alcanzables. Mismo caveat ya documentado para los skills `definicion-alcance`/`nuevo-ticket` (necesitan una sesión nueva para quedar invocables por nombre), ahora confirmado que también aplica a subagentes. Workaround real: `Agent` con `subagent_type: "general-purpose"` e inyectar el contenido completo del rol en el prompt — logra aislamiento de contexto genuino aunque no la etiqueta nativa.
- **Íconos diseñados de verdad por el rol ux-ui-designer** (vía el workaround), no inventados por el hilo principal: line icons consistentes (`viewBox="0 0 24 24"`, `stroke="currentColor"`, `stroke-width="1.75"`) para Inicio/Clientes/Métricas/Proveedores de login, 5 íconos de tarjetas de estadística de métricas, 2 íconos de estado (éxito/error), una ilustración de estado vacío (viewBox propio, colores del sistema), y los logos de marca de Google/Facebook (multicolor, fieles a marca). El diseñador verificó visualmente el set completo antes de entregarlo y **se autocorrigió**: descartó un primer diseño de "tasa de éxito/error" (gauge con aguja) porque no se leía bien a 22px, lo reemplazó por un símbolo de porcentaje — documentado explícitamente el porqué, no un cambio silencioso.
- **`fragments/icons.html` (nuevo)**: un `th:fragment` por ícono, mismo patrón de reutilización que `fragments/admin-shell.html`. Markup copiado literal de la entrega del diseñador.
- **Sidenav wireado** (`admin-shell.html`): los 3 links existentes (Clientes/Métricas/Proveedores) ya muestran su ícono vía `th:replace`. `icon-inicio` queda listo en el fragmento pero sin usar todavía — el link "Inicio" lo agrega el ticket 025.
- **Decisión técnica real, no anticipada en el ticket**: los íconos del diseñador son 24x24 (viewBox compartido de toda la familia), pero el sidenav los necesita a 20x20. Como `th:replace` sustituye el tag completo (no se puede inyectar una clase de tamaño en el host), se resolvió con una regla CSS (`.admin-sidenav a svg { width/height: 20px }`, las propiedades CSS ganan sobre los atributos `width`/`height` del SVG) en vez de tocar el fragmento compartido — mantiene `icons.html` reutilizable a su tamaño nativo para cualquier página futura.
- Verificado en vivo en el navegador (logos de Google/Facebook renderizados en una pestaña real para confirmar que se ven fieles a la marca, íconos del sidenav revisados a tamaño real con estado activo/hover).
- 259/259 tests en verde (sin tests nuevos — presentación pura, sin backend tocado).

## Ticket 025: navegación admin↔consumidor

Segundo ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-1, HU-2). Ejecutado con delegación real al rol `frontend-dev` (workaround `general-purpose` + persona inyectada, ver ticket 024).

- **HU-1**: `cuenta.html` gana una tarjeta "Panel de administración" con el botón "Ir al panel de administración" — oculta por defecto en el HTML servido, revelada por JS solo si `AuthCoreUi.currentRole()` de **esta sesión** es `TENANT_ADMIN`/`PLATFORM_ADMIN`. El rol siempre se lee del JWT de la sesión actual, nunca se asume entre apps/tenants — resuelve directamente la ambigüedad original planteada por el Product Owner.
- **HU-2**: nueva ruta `GET /ui/admin` (`UiPagesController.adminHome`) → `admin-home.html`, reutiliza el shell compartido. Tarjetas filtradas client-side por rol (mismo mecanismo que "Clientes" en el sidenav desde el ticket 020): `platform_admin` ve 3 (Clientes/Métricas/Proveedores), `tenant_admin` ve 2 (sin Clientes). Nuevo link "Inicio" agregado como primer ítem del sidenav, siempre visible (a diferencia de "Clientes", no depende del rol).
- **Caso `role=NONE` manejado explícitamente**: la pantalla carga (sin sesión de servidor real detrás, mismo trust-boundary del ticket 009) pero ninguna tarjeta coincide con ese rol — se muestra un mensaje "No tienes acceso a ninguna sección del panel..." con link a login, en vez de una pantalla en blanco o tarjetas que solo terminarían en un 403 real del backend.
- **Bug real pre-existente encontrado y corregido en vivo** (desde el ticket 020, no introducido por este ticket): `.hidden { display: none }` tenía menor especificidad CSS que `.admin-sidenav a { display: flex }` y `section.card { display: flex }` — el link "Clientes" del sidenav **nunca se ocultó de verdad vía CSS** para un `tenant_admin`, pese a que el JS sí actualizaba la clase correctamente. Nadie lo notó hasta que las nuevas tarjetas de HU-2 (también `display: flex`) expusieron el mismo conflicto. Corregido con `!important` en `admin.css` y `app.css` — correcto para una clase utilitaria cuyo contrato es "siempre oculto sin importar qué más aplique". Verificado en vivo con un `tenant_admin` real: "Clientes" ahora sí desaparece del sidenav y de las tarjetas de inicio.
- Verificado en vivo con los 3 roles reales (`platform_admin`, `tenant_admin`, sin rol) — cada uno viendo exactamente lo que le corresponde, incluyendo el mensaje de "sin acceso".
- 260/260 tests en verde (1 nuevo: `rendersTheAdminHomePageWithTheAdminShellNotTenantTheming`).

## Ticket 026: layout centrado y convención de botones sin desborde

Tercer ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-3). Ejecutado con delegación real al rol `frontend-dev` (workaround `general-purpose` + persona inyectada, ver ticket 024).

- **Centrado**: `.admin-content` gana `margin: 0 auto` junto a su `max-width: 900px` ya existente — una vez que el item flex se "congela" en su `max-width`, flexbox reparte el espacio libre restante en la línea entre los márgenes automáticos (CSS Flexbox §8.1), quedando izquierda/derecha iguales sin tocar el resto del layout flex (`.admin-shell`/`.admin-body-row`) ni el breakpoint responsive de 720px.
- **Convención de botones**: nueva clase `.button-group` (`display:flex; gap` + `flex:1` en cada `<button>` hijo) — un botón que es la única acción de un formulario sigue ocupando el ancho completo (comportamiento ya existente, sin tocar); un GRUPO de botones (ej. Guardar + Desactivar en proveedores) ahora quedan del mismo ancho entre sí, nunca uno full-width mezclado con uno auto-width. Deliberadamente NO aplicada a `.confirm-actions`/`.dialog-actions` — esos ya son pares de botones auto-width del mismo estilo, alineados a la derecha por diseño, no el problema que este ticket resuelve.
- **`admin-identity-providers.html`**: el botón "Guardar" de cada tarjeta (Google, Facebook) se movió fuera de su `<form>` a un `.button-group` junto a "Desactivar", ligado funcionalmente al formulario vía el atributo HTML5 `form="..."` — el navegador dispara el evento `submit` en el formulario referenciado sin importar dónde viva físicamente el botón en el DOM, así que el JS existente (`.addEventListener("submit", ...)`) no necesitó ningún cambio.
- **Revisión de las 4 páginas del panel**: `admin-tenants.html`, `admin-metrics.html` y `admin-home.html` no necesitaron cambios — revisadas explícitamente, sin otro desborde/inconsistencia de botones encontrado.
- **Verificación en vivo con sesión real de admin** (el agente encontró un límite real de permisos al intentar fabricar una sesión de `platform_admin` — sessionStorage/rol en base de datos — y correctamente se detuvo en vez de rodearlo; verificó con previews estáticas en su lugar. El orquestador repitió la verificación con una sesión autenticada real): centrado confirmado computacionalmente (`getComputedStyle` en `.admin-content` real, `margin-left`/`margin-right` = 185.5px exactos en un viewport de 1471px), botones Guardar/Desactivar de Proveedores de login confirmados del mismo ancho, tabla de Clientes sin ningún botón cortado.
- 260/260 tests en verde (sin tests nuevos — cambio puramente CSS/HTML, ninguna página cambia de status/contenido textual verificable).

## Ticket 027: alta de tenant vía botón + modal

Cuarto ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-5). Ejecutado con delegación real al rol `frontend-dev`.

- Botón "+ Añadir cliente" agregado junto al `<h2>` (nuevo wrapper `.section-header`, flex con `justify-content: space-between` — un botón desnudo fuera de un `<form>` no hereda el stretch full-width que da `form { display:flex; flex-direction:column }`, así que necesitaba su propio contenedor).
- El formulario de alta embebido al fondo de la página se eliminó por completo, reemplazado por `<dialog id="create-tenant-dialog" class="form-dialog">` — mismo patrón exacto que `edit-tenant-dialog` (ticket 021): mismos campos, mismos defaults de TTL.
- El formulario se resetea al ABRIR el modal (no solo al enviar) — evita que reabrir el modal tras cancelar muestre datos de un intento anterior; decisión no pedida explícitamente en el AC pero necesaria para un flujo de "alta nueva" consistente.
- Al crear con éxito: el modal se cierra, la tabla se recarga sin recargar la página, el mensaje de éxito vive en el `#status` de la página (no dentro del modal, ya que este se cierra).
- **Verificación en vivo**: el agente topó el mismo límite de permisos que el ticket 026 (no pudo fabricar una sesión de `platform_admin`) y correctamente no lo rodeó — verificó por código + HTTP directo + tests. El orquestador completó la verificación con una sesión real: abrir modal, cancelar (sin llamada al backend, tabla sin cambios), crear un tenant real (mensaje "Cliente creado.", modal cerrado solo, tabla actualizada sin recargar la página).
- 260/260 tests en verde (sin tests nuevos — ningún endpoint cambió, `UiPagesControllerTest` solo verifica status/theming, no el detalle del formulario).

## Ticket 029: rediseño de proveedores de login social

Sexto ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-7). Ejecutado con delegación real al rol `frontend-dev`, en un worktree Git propio corriendo en paralelo con el ticket 028 (sin dependencia entre ellos — ambos solo dependen de tickets ya cerrados).

- **Logos de marca**: `logo-google`/`logo-facebook` (ya diseñados en el ticket 024) agregados junto al `<h3>` de cada tarjeta, en un nuevo wrapper `.card-title` (flex) que toma el `margin-bottom` que antes cargaba el `<h3>` solo — páginas que sigan usando un `<h3>` a secas no cambian.
- **Convención `.button-group` del ticket 026**: confirmada intacta, sin necesitar cambios — Guardar/Desactivar ya quedaban del mismo ancho desde ese ticket.
- **Layout de 2 columnas evaluado y descartado explícitamente**: el patrón `.form-grid`/`.span-2` del modal de edición de tenant funciona ahí porque agrupa 7 campos con pares cortos relacionados (ej. los TTLs). Cada tarjeta de proveedor solo tiene 2 campos, y ambos son credenciales OAuth opacas y largas (40-100+ caracteres) — partirlas a la mitad no ahorra espacio vertical y solo fuerza wrap/scroll dentro del input, dificultando leer/pegar el valor completo. Decisión de diseño documentada explícitamente en el HTML, no una omisión.
- Apple sigue sin card propia, backend sin cambios.
- **Verificación en vivo**: el agente topó el mismo límite de permisos ya documentado (no pudo fabricar sesión de admin) — probó además rutas no destructivas (`file://`, servidor HTTP local, `data:` URL) para al menos inspeccionar visualmente el render, todas bloqueadas por el sandbox del entorno, y correctamente no insistió rodeándolas. Verificó por MockMvc (render server-side 200) + inspección directa del SVG de los logos. El orquestador reconstruyó el diff limpio (la rama se había creado antes de que el ticket 027 mergeara, así que se rebaseó sobre `main` actualizado) y confirmó 260/260 tests en verde de nuevo tras el rebase.
- 260/260 tests en verde (sin tests nuevos — ninguna aserción existente chocó con el wrapper agregado).

## Ticket 028: métricas gráficas

Quinto ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-6). Ejecutado con delegación real al rol `frontend-dev`, en un worktree Git propio en paralelo con el ticket 029.

- **Selector de tenant**: `platform_admin` ve un `<select>` real poblado desde `GET /api/v1/admin/tenants` (ya existente); `tenant_admin` ve su tenant como texto fijo — decidido llamar `GET /api/v1/admin/tenants/{id}` sobre su propio id (ya permitido por `AdminAccessPolicy`, no una capacidad nueva) para mostrar el **nombre** en vez del UUID crudo, ya que HU-6 retira explícitamente el UUID de la pantalla y dejarlo solo para este rol habría sido inconsistente con lo que ve `platform_admin`. Cae de vuelta al UUID si esa llamada falla.
- **Accesos rápidos de rango** (7/30/90 días): chips que rellenan los date pickers existentes y disparan la consulta.
- **`static/js/charts.js` (nuevo)**: funciones puras dato → string SVG, cero dependencias, mismo patrón que `fragments/icons.html`. Éxito/fallo se dibuja como **dona** (es una proporción de un mismo total, no una comparación entre categorías independientes) con la técnica estándar de dos `<circle>` + `stroke-dasharray`; actividad por proveedor se dibuja como **barras horizontales** (nombres de proveedor de longitud variable, una barra horizontal no necesita rotar ni envolver la etiqueta). Con un solo proveedor activo, se muestra una frase en vez de una "comparación" de un solo elemento. Todo el texto SVG pasa por un escape explícito antes de interpolarse.
- **Tarjetas de estadística** con ícono (logins totales, usuarios activos, usuarios registrados, latencia promedio) — mismos íconos del ticket 024.
- **Estado vacío**: reutiliza la ilustración `illus-vacio` (existía desde el ticket 024, nunca usada en ningún template hasta ahora) + su animación de entrada ya definida.
- **Bug real encontrado en vivo** (no por el agente, que topó el límite de permisos de siempre y no pudo verificar con sesión real — encontrado por el orquestador al completar la verificación): la ilustración + mensaje del estado vacío se renderizaban como contenido en bloque apilado normal (alineado a la izquierda, sin espacio entre ambos) en vez de leerse como un estado vacío deliberado. Corregido agregando el `display:flex; flex-direction:column; align-items:center` que le faltaba a `.empty-state` — la animación de entrada ya existía pero nunca tuvo el layout que la acompañara visualmente.
- **Verificado en vivo con datos reales** (tenant Acme, 43 logins acumulados de las pruebas de esta sesión): dona mostrando 86% de éxito con el segmento rojo correcto, tarjetas de estadística con los números reales, caso de un solo proveedor mostrando la frase en vez de una barra, y el estado vacío ya centrado tras el fix. Verificado también con `tenant_admin`: nombre del tenant fijo, sin selector.
- 260/260 tests en verde (sin tests nuevos — `UiPagesControllerTest` solo verifica `"Métricas de uso"`, sin cambios).

## Ticket 030: animaciones/transiciones + revisión final de accesibilidad

Séptimo y último ticket de la fase 2 del rediseño de UI (`docs/definiciones/rediseno-ui-fase-2.md`, HU-8) — cierra el epic completo (tickets 024-030). Ejecutado con delegación real al rol `frontend-dev`.

- **Entrada de `<dialog>` con `@starting-style`**: técnica nativa correcta para animar la apertura de un `<dialog>` vía `showModal()` — a diferencia de un `<div>` normal, el navegador cambia `display` de `none` a `block` de forma síncrona al abrir, así que una transición sin `@starting-style` no tiene "frame anterior" del que interpolar. Anima el `<dialog>` (opacity + scale sutil) y su `::backdrop` por separado, con `allow-discrete` en `display`/`overlay` (el recipe canónico completo de Chrome/MDN). Aplica a `confirm-dialog` y `form-dialog` — cubre también el modal de alta de tenant del ticket 027 sin regla extra, misma clase. Soporte de navegador confirmado (ago-2026): Chrome/Edge 117+, Safari 17.5+, Firefox 129+, todos con +1 año en producción — tratado como técnica principal, no fallback; sin soporte el diálogo simplemente aparece instantáneo, nunca invisible.
- **Hover/focus consistente**: encontrados 2 casos reales sin transición durante la auditoría — `.admin-sidenav a` no tenía ninguna, y la transición base de `button` solo cubría `background-color`/`transform`, dejando `color`/`border-color`/`box-shadow` (usados por `.secondary`/`.danger`/`.admin-logout`) con cambios bruscos. Ambos corregidos.
- **`prefers-reduced-motion`**: aplicado a dialogs+backdrop y sidenav; para `button` se desactivó la transición completa (no solo los canales nuevos) porque el criterio de HU-8 dice "cualquier elemento animado", y partir el shorthand en "esto sí, esto no" bajo la media query era más frágil que apagarlo entero — mismo principio que ya regía `.is-loading`/`illus-vacio`.
- **2 hallazgos reales de accesibilidad, no solo confirmación de lo ya correcto**: (1) el porcentaje de éxito del centro de la dona ("86%") solo existía como `<text>` dentro del SVG decorativo (`aria-hidden`) — no tenía contraparte en el DOM accesible (la leyenda solo mostraba el conteo crudo). Agregado junto al conteo de "Éxito" en la leyenda, calculado con la misma fórmula que `charts.js` para que nunca diverjan. (2) `<label for="tenant-fixed-name">` apuntaba a un `<p>` — `for` solo crea la relación de accesibilidad hacia un control de formulario nativo, así que esa asociación nunca funcionó para lectores de pantalla. Corregido con `aria-labelledby` explícito. El resto de la revisión (14 íconos de `fragments/icons.html`, gráfica de barras por proveedor, textos de estado de proveedor) se confirmó ya correcto, sin cambios necesarios.
- **Verificado en vivo con sesión real** (el agente topó el límite de permisos de siempre y usó `getAnimations()`/`getComputedStyle` sobre un harness estático — el orquestador completó la verificación real): capturada en pantalla la animación de apertura del modal de alta de tenant a mitad de transición (backdrop parcialmente oscurecido, contenido de la tabla aún visible a través) confirmando que corre de verdad, no aparece instantánea; hover del sidenav con transición de fondo confirmada; porcentaje de éxito confirmado en vivo con datos reales ("Éxito: 40 (87%)", coincide exacto con el 87% del centro de la dona).
- 260/260 tests en verde (sin tests nuevos — cambio de presentación puro, sin regresión en `UiPagesControllerTest` ni en los tests de 020-029).

## Ticket 031: botones de acción en fila (icono + texto) en la tabla de clientes

Ajuste puntual post-epic (fase 2 de UI ya cerrada en el ticket 030) — pedido directo del Product Owner al notar el layout apilado de `/uid/admin/tenants`. Ejecutado con delegación real al rol `frontend-dev`.

- **El fix del 022 no se revirtió a ciegas.** `.actions-cell-inner` en columna (ticket 022) evitaba el desborde causado por dos botones de etiqueta ancha ("Editar" + "Desactivar"/"Reactivar") lado a lado. Este ticket vuelve a fila, pero resuelve el desborde por otra vía — botones compactos icono+texto corto (`.icon-btn`: "Editar"/"Baja"/"Alta") — en vez de deshacer esa corrección.
- **3 íconos nuevos** (`icon-editar`, `icon-desactivar`, `icon-reactivar`) en `fragments/icons.html`, mismas convenciones del set del ticket 024. Como las filas de esta tabla se arman en JS (`loadTenants()` vía `.innerHTML`, no Thymeleaf por fila), los íconos se renderizan una vez server-side en `<template>` ocultos y se leen como HTML string reutilizable — mantiene la fuente única en `fragments/icons.html` sin duplicar SVG literal dentro del script.
- **Hallazgo real de seguridad, corregido de paso:** `escapeHtml()` solo escapaba texto de nodo (`&`, `<`, `>`), no comillas. Al reutilizarse para construir el nuevo `aria-label` de cada botón (`"Desactivar " + nombre del tenant`), un nombre con `"` podía romper el atributo HTML generado. Extendida para escapar también `"`/`'`.
- **Hallazgo real de layout, encontrado solo al medir en vivo (no se hubiera detectado con revisión estática):** el primer diseño de `.icon-btn` sí desbordaba (~50px medidos con `scrollWidth - clientWidth` en Chrome) contra la lista real de tenants del entorno de dev, que incluye nombres largos heredados de tickets anteriores (`TicketVerify027`/`Verify App 027`). Causa: `.admin-content` tiene `max-width: 900px` fijo, así que el ancho de la columna "Acciones" no crece con el viewport. Corregido con una clase `.actions-cell` (padding horizontal propio, más angosto que el `td` compartido de toda la tabla) y afinando `.icon-btn` — iterado en vivo hasta 0px de desborde exacto.
- **Verificado en vivo con sesión `platform_admin` real** (`qa-visual-031@example.com`, promovido vía `UPDATE` puntual autorizado explícitamente por el Product Owner tras un primer intento bloqueado por el clasificador de permisos — el usuario se deja como `PLATFORM_ADMIN` reutilizable en la BD local de desarrollo para no repetir este obstáculo en tickets futuros): tabla completa con tenants activos e inactivos mezclados, botones en fila sin cortes ni scroll horizontal, íconos con el color correcto vía `currentColor` (índigo en "Editar", rojo en "Baja" activa).
- 260/260 tests en verde (sin tests nuevos — cambio de presentación puro).
- **Gotcha operativo documentado, no resuelto (fuera de alcance del ticket):** el servidor de desarrollo local cachea templates de Thymeleaf en memoria y `bootRun` no recopia recursos estáticos al `build/resources/main` mientras el proceso sigue vivo — forzó reinicios manuales para iterar. `spring-boot-devtools` + `spring.thymeleaf.cache=false` en el perfil de dev lo resolvería; pendiente como mejora de tooling, no de producto.

## Ticket 032: iconografía e ilustraciones — resto del panel admin y páginas de usuario final

Segundo ajuste puntual post-epic (fase 2 de UI cerrada en el 030), en paralelo con el 031 (worktree separado para no pisar los mismos archivos). Ejecutado con delegación real a los roles `ux-ui-designer` (diseño) y `frontend-dev` (wiring), en ese orden — mismo patrón de dos pasos que el ticket 024.

- **11 fragmentos SVG nuevos** en `fragments/icons.html` (`icon-crear-cuenta`, `icon-mi-cuenta`, `icon-panel-admin`, `icon-verificacion-correo`, `icon-cambiar-correo`, `icon-correo`, `icon-telefono`, `icon-password`, `icon-2fa`, `icon-restablecer-password`, `illus-sin-acceso`), diseñados y documentados vista por vista por una instancia real del rol `ux-ui-designer` antes de wirear nada — mismas convenciones del ticket 024 (`viewBox 0 0 24 24`, `stroke="currentColor"`, `aria-hidden`). Reutilizados sin fragmento nuevo: los 4 íconos de nav ya existentes para los headers de las 4 vistas admin, e `icon-logins-totales` para el header de `login.html`.
- **Headers con ícono** en las 4 vistas admin + 7 páginas de usuario final (`.card-title`, clase compartida entre `admin.css` y `app.css`). **Íconos de campo** junto al `<label>` (nunca dentro del `<input>`, decisión de diseño explícita) para correo/teléfono/contraseña. **`illus-sin-acceso`** en el estado bloqueado de `admin-home.html` para un rol sin acceso — ilustración nueva, no reusa `illus-vacio`, porque comunica algo distinto ("esto no es para ti" vs. "no hay datos aún").
- **`AuthCoreUi.showStatus()`** (`api.js`, helper compartido por las 11 páginas) antepone `icon-exito`/`icon-error` al mensaje sin cambiar su firma ni ninguno de sus ~20 call-sites existentes: cada página declara una vez un `<template id="status-icons">` (íconos renderizados server-side vía `th:insert`), y la función los lee/clona por id. Degrada con gracia — una página sin ese `<template>` simplemente no antepone ícono, el comportamiento previo a este ticket.
- **Gap real encontrado y cerrado, no solo lo ya planeado:** el estado vacío de `admin-tenants` (`#empty-state`) nunca había quedado wireado con `illus-vacio` pese a que el comentario original del ticket 024 lo daba por hecho — era solo texto plano. Cerrado aquí (mismo patrón que el empty-state de `admin-metrics`, ticket 028), junto con el header e ícono de `showStatus()` que a esta página también le faltaban — secuenciado a propósito después de que el ticket 031 (que tocaba el mismo archivo) ya había mergeado, para no generar un conflicto.
- **Decisiones de alcance explícitas del `ux-ui-designer`, no implementadas a propósito:** logos de proveedor (Google/Facebook) en la leyenda de `admin-metrics` — descartado porque `byProvider` incluye "PASSWORD" (sin logo propio) y exigiría lógica de fallback en `charts.js`, fuera del alcance de un cambio puramente de iconografía.
- 260/260 tests en verde (sin tests nuevos — cambio de presentación puro).

## Ticket 033: hot-reload de dev — templates Thymeleaf y recursos estáticos sin reiniciar el JVM

Ticket de tooling puro (sin cambio de producto), nacido de un hallazgo real durante el ticket 031. Ejecutado con delegación real al rol `backend-dev`.

- `spring-boot-devtools` agregado como `developmentOnly` en `backend/build.gradle` (mismo patrón que `spring-boot-docker-compose`, ya existente).
- **Decisión clave: no hizo falta ninguna config explícita ni separación de perfiles `dev`/`prod`** (el proyecto solo tiene un `application.properties`) — devtools aplica automáticamente sus "property defaults" (`spring.thymeleaf.cache=false` y equivalentes) en cuanto está en el classpath de `bootRun`, confirmado en el log real. Aislamiento de producción/tests verificado explícitamente (jar empaquetado y `testRuntimeClasspath` inspeccionados, sin devtools en ninguno de los dos) — no solo asumido por la convención de `developmentOnly`.
- Verificado en vivo con `bootRun` real: edición de prueba en `templates/login.html` y `static/css/app.css`, `./gradlew processResources`, log de devtools reiniciando el contexto (mismo PID, sin matar el proceso), cambio confirmado vía `curl` sin reinicio manual.
- `docs/README.md` actualizado con el matiz práctico: sin IDE con auto-build, hace falta `./gradlew processResources` (o `-t` en continuo) tras cada edición para que devtools la detecte.
- **Hallazgo operativo real durante la propia verificación:** un proceso `bootRun` viejo (previo al cambio) seguía ocupando el puerto 8080 — detectado y detenido antes de verificar con la config actualizada. Propuesta de mejora continua (no implementada): un check en el flujo de "arranca la app" que detecte un puerto ya ocupado antes de lanzar `bootRun`.
- `./gradlew clean test`: todo en verde.

## Ticket 034: homologa clase `success`/`error` en `showStatus()` (`[role="status"]`/`[role="alert"]`)

Hallazgo de consistencia interna, encontrado durante la verificación en vivo del ticket 032. Ejecutado con delegación real al rol `frontend-dev`.

- **Mecanismo elegido: clase explícita simétrica** (`success`/`error`) en ambos casos, no depender de `role` para el color del error — `role="status"` ya se comparte con el estado de carga (`is-loading`, spinner de `verify-email-confirm.html`/`change-email-confirm.html`); depender solo de `role` no habría permitido distinguir "cargando" (sin color) de "éxito" (verde) en CSS sin tocar además esas plantillas. Con clase explícita, `role` queda puramente para semántica ARIA y la clase determina el color.
- `showStatus()` (`api.js`) agrega `error`/`success` de forma simétrica; `admin.css`/`app.css`: color de error gateado por `[role="alert"].error` (la regla base de layout/ícono, compartida con `[role="status"]`, queda intacta). Sin cambio visual — mismo color/layout/ícono que antes.
- Confirmado que ningún template tiene `role="alert"` hardcodeado (solo lo pone `showStatus()`) — el cambio no puede romper ningún otro caso.
- Verificado en vivo en `login`, `password-reset-request`, `register`. **No verificado en páginas admin** (`admin-tenants` y demás) por falta de credenciales `platform_admin` — mismo CSS/JS compartido por las 11 páginas, resultado esperado idéntico, mencionado explícito en vez de asumido.
- 260/260 tests en verde, sin cambios de comportamiento backend.

## Ticket 035: modelo de datos — tabla `external_identity`

Primer ticket de la épica de login social real (`docs/definiciones/login-social-real.md`, Diseño técnico, decisión 6). Solo modelo de datos — ningún endpoint ni flujo de login se toca todavía; eso llega con los tickets 037-042 que dependen de este.

- **Tabla nueva, no columnas en `app_user`**: permite vincular más de un proveedor social a la vez a la misma cuenta, requerido explícitamente por la definición. Mismo patrón de `tenant_id` denormalizado que `login_event`/`tenant_identity_provider` — evita un join extra a `app_user` para filtrar por tenant.
- **`provider_user_id` es el `sub` (Google) / `id` (Facebook) del proveedor, nunca el email** — el email puede cambiar del lado del proveedor; el identificador estable es lo único seguro para resolver un login social entrante.
- **Reutiliza `IdentityProviderType`** (ticket 001/006), el mismo enum que ya usa `tenant_identity_provider` — no se crea un tipo paralelo.
- **Dos constraints de unicidad, ambas a nivel de base de datos** (`external_identity_tenant_provider_unique` y `external_identity_user_provider_unique`), no solo validadas en Java: `UNIQUE(tenant_id, provider, provider_user_id)` (la misma cuenta social no puede vincularse dos veces dentro del mismo tenant) y `UNIQUE(user_id, provider)` (un `app_user` no puede tener más de un vínculo con el mismo proveedor, pero sí con proveedores distintos).
- Migración `V8__external_identity.sql`, entidad `ExternalIdentity` y `ExternalIdentityRepository` (`findByTenantAndProviderAndProviderUserId` para resolver un login entrante, `findByUser` para listar proveedores vinculados en `/ui/cuenta`, ticket futuro).
- Tests de repositorio (`ExternalIdentityRepositoryTest`) confirman ambas constraints con el mismo patrón que `UserRepositoryTest`: `entityManager.flush()` explícito (bypasea la traducción de excepciones de Spring) + `ConstraintViolationException` con el nombre exacto de la constraint, no solo "alguna excepción".
- **Migración verificada contra la base de datos local real** (`auth-core-mc-postgres-1`, no solo Testcontainers) vía el CLI de Flyway en Docker apuntando al Postgres de `compose.yaml` — `flyway info` mostró la V8 como `Pending` antes y `flyway migrate` la aplicó limpio sobre el estado real (V1-V7 ya aplicadas), sin arrancar el servidor completo.

## Ticket 036: `TenantAwareClientRegistrationRepository` + wiring en `SecurityConfig`

Nace de `docs/definiciones/login-social-real.md` (Diseño técnico, decisiones 1, 2 y 5) — primer ticket de la cadena de login social real (Google/Facebook) para usuario final, tras la fase de definición con VoBo del Product Owner. Ejecutado con delegación real al rol `backend-dev`.

- **`TenantAwareClientRegistrationRepository implements ClientRegistrationRepository`** — mismo espíritu que `TenantAwareRegisteredClientRepository` (ticket 007): reconstruye el `ClientRegistration` en cada `findByRegistrationId(...)`, sin cache. `registrationId = "{identityClient.id}::{provider}"` (proveedor case-insensitive); resuelve `IdentityClient` → `Tenant` → `TenantIdentityProvider` (valida `enabled`), descifra `client_secret` vía `TenantSecretEncryptor`, y parte de `CommonOAuth2Provider.GOOGLE`/`.FACEBOOK` sobreescribiendo solo `clientId`/`clientSecret`/`redirectUri`.
- **`redirectUri` deliberadamente re-seteado al mismo placeholder que `CommonOAuth2Provider` ya trae por default** (`"{baseUrl}/login/oauth2/code/{registrationId}"`), en vez de dejarlo implícito — mismo valor exacto para todos los tenants (OQ-1), resuelto por Spring en cada request desde el host real; ninguna config nueva (`app.base-url` u otra) involucrada. Explícito en el código para no depender silenciosamente de que el default de Spring no cambie.
- **Requisito de seguridad verificado con test dedicado:** UUID inexistente y proveedor existente-pero-deshabilitado devuelven `null` por la misma cadena de `Optional`/`orElse(null)` — sin excepción, log o efecto distinto entre ambos casos. `anUnknownUuidAndADisabledProviderAreIndistinguishable` los ejecuta juntos y compara el resultado (ambos `null`, `isEqualTo`) para dejarlo probado, no solo argumentado. Apple (fuera de alcance, sin `CommonOAuth2Provider` propio, y ya rechazado por `TenantIdentityProviderService`) cae por el mismo camino de `null`.
- **`AuthorizationServerConfig` NO se tocó** (confirmado leyendo su `securityMatcher`, no solo asumido): su cadena `@Order(1)` está acotada a `getEndpointsMatcher()` de `OAuth2AuthorizationServerConfigurer` — únicamente `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/.well-known/**`, etc. — que estructuralmente no incluye `/oauth2/authorization/**` (inicio del login social, prefijo distinto: "authorization" vs. "authorize") ni `/login/oauth2/code/**` (callback), ambos del lado *cliente* OAuth2 (`spring-boot-starter-oauth2-client`, dependencia nueva), no del lado *servidor*. `TenantAwareRegisteredClientRepositoryTest` (Authorization Code + PKCE) y `AuthControllerTest` (`/api/v1/login`, grant directo) corridos explícitamente: mismo resultado, mismo conteo de tests, sin tocar esos archivos.
- **`SecurityConfig`**: `permitAll` agregado para `/oauth2/authorization/**` y `/login/oauth2/code/**` (mismo bloque que `/ui/**`), y `.oauth2Login(oauth2Login -> oauth2Login.clientRegistrationRepository(clientRegistrationRepository))` apuntando al repositorio nuevo — recibido como parámetro del método `@Bean`, mismo patrón ya usado con `ObjectMapper`. Sin `successHandler`/`failureHandler` propios todavía (llegan en el ticket 037, fuera de alcance aquí) — la sesión de Spring que crea este DSL no se usa como auth continua (Decisión 5), solo como la correlación que Spring ya necesita entre redirect y callback.
- **Dependencia nueva:** `spring-boot-starter-oauth2-client` en `backend/build.gradle` — no era transitiva de `spring-boot-starter-security-oauth2-authorization-server` (confirmado con `./gradlew dependencies`); trae `ClientRegistrationRepository`/`ClientRegistration`/`CommonOAuth2Provider`/`.oauth2Login(...)`.
- **9 tests `@WebMvcTest` con `@Import(SecurityConfig.class)`** (`AuthControllerTest`, `EmailChangeControllerTest`, `EmailVerificationControllerTest`, `IdentityProviderControllerTest`, `PasswordResetControllerTest`, `RegistrationControllerTest`, `TokenControllerTest`, `TwoFactorControllerTest`, `UiPagesControllerTest`) necesitaron un `@MockitoBean private ClientRegistrationRepository clientRegistrationRepository;` nuevo — mismo motivo/patrón que el `JwtDecoder` mockeado desde el ticket 012: el bean ya no es opcional para construir la cadena de filtros en esa slice, nunca se stubbea, solo satisface la inyección.
- 272/272 tests en verde (260 previos + 12 nuevos de `TenantAwareClientRegistrationRepositoryTest`).

## Ticket 037: `SocialLoginSuccessHandler`/`SocialLoginFailureHandler`

Nace de `docs/definiciones/login-social-real.md` (HU-1, HU-2, HU-3; Diseño técnico, decisiones 3, 4 y 5) — segundo ticket de la cadena de login social real, sobre el wiring de `oauth2Login` que dejó el ticket 036. Ejecutado con delegación real al rol `backend-dev`.

- **`SocialRegistrationId`** (`oauth2`) — el parseo `"{identityClientId}::{provider}"` que `TenantAwareClientRegistrationRepository` ya tenía se extrajo a esta clase compartida, para que los handlers (que necesitan la misma identidad tenant+proveedor otra vez, ya del lado del callback) no dupliquen el formato. `TenantAwareClientRegistrationRepository` se refactorizó para delegarle el parseo y quedarse solo con el paso extra que sí es suyo (mapear a `CommonOAuth2Provider`) — mismo comportamiento observable, cubierto por sus 12 tests existentes sin tocarlos.
- **`SocialLoginUserResolver`** (`service`, `@Transactional`) — la mitad `app_user`/`external_identity`: primero busca por `(tenant, provider, providerUserId)` (camino rápido de un login social repetido, sin volver a exigir el email verificado — esa exigencia solo aplica a un vínculo *nuevo*); si no hay vínculo, busca `app_user` por `(tenant, email)` y crea o auto-vincula. `email_verified` de una cuenta nueva refleja exactamente lo que reportó el proveedor (`markEmailVerified()` solo si `email_verified=true`/Facebook con email presente — nunca se tocó el constructor de `User`). El intento de duplicar `external_identity` (`UNIQUE(user_id, provider)`, race o cliente que se saltó el camino rápido) se captura y se trata como login repetido normal, no como error.
- **Caso borde no cubierto explícitamente por el ticket/definición, resuelto e inferido:** un `app_user` existente con ese email pero el proveedor **no** lo reporta verificado — no se puede auto-vincular (R-1) ni crear una cuenta duplicada (`app_user_tenant_email_unique`). Se bloquea con `SocialLoginBlockedException("social_login_email_conflict", ...)`. **Marcado para confirmación explícita del Product Owner al cerrar el ticket** — no es un criterio de aceptación escrito, es la única salida segura dado el modelo de datos existente.
- **`SocialLoginSuccessHandler`** (`oauth2`) — vuelve a resolver `IdentityClient`/`Tenant` desde `OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()`; extrae el perfil de `OidcUser` (Google: `email`, `email_verified` claim, `sub`) u `OAuth2User` (Facebook: atributo `email` — su sola presencia ya es la señal de verificado, Facebook no tiene un claim equivalente; `getName()` como `provider_user_id`). Facebook sin `email` bloquea con `social_login_no_email`, sin inventar un identificador alterno (HU-1). Éxito: `LoginEvent` (`provider` real `GOOGLE`/`FACEBOOK`), código de un solo uso vía `RedisTokenStore.issue("social-login-exchange", userId, Duration.ofSeconds(60))`, redirect a `/ui/social-callback?client_id={clientId público}&code=...` — nunca un token real en la URL.
- **`SocialLoginFailureHandler`** (`oauth2`) — HU-3 distingue dos casos explícitamente, no los colapsa en uno: **consentimiento denegado** (`OAuth2ErrorCodes.ACCESS_DENIED`, la `OAuth2AuthorizationRequest` sí correlacionó — sesión válida, `state` coincidió) parsea el `registrationId` del último segmento de `request.getRequestURI()` (siempre `/login/oauth2/code/{registrationId}`, éxito o fallo) y redirige *themed* a `/ui/login?client_id=...&error=social_login_cancelled`; **cualquier otro caso** — sin `OAuth2AuthorizationRequest` correlacionada (sesión expirada/callback manipulado, Decisión 4: Spring corta el flujo antes de tocar tenant o usuario) o credenciales del tenant rotas — redirige a la página genérica sin theming, sin inferir `client_id` vía `Referer`. `LoginEvent` de fallo solo se registra cuando el tenant sí se resolvió (consentimiento denegado); el otro bucket deliberadamente nunca toca tenant, así que no hay nada que registrar con `tenant_id NOT NULL`.
- **Placeholder deliberado:** `/ui/social-login-error` no tiene plantilla — la página sin theming es del ticket `039`, fuera de alcance aquí. El redirect da `404` hasta que ese ticket aterrice; aceptado explícitamente, coordinado como contrato estable para que `039` construya contra él.
- **2FA (OQ-8) — hallazgo real, no una decisión de producto:** el ticket pedía enganchar "el mismo criterio que ya aplica a login con password" antes de dar la sesión por completa. Ese criterio no existe: `AuthController#login` emite tokens sin ningún gate de 2FA; `TwoFactorController`/`TwoFactorPreferenceService` son un mecanismo 100% autoservicio desde `/ui/cuenta` (ticket 005), nunca una puerta de login. Construir una puerta nueva solo para el login social habría violado la instrucción explícita del propio ticket ("no inventes un mecanismo nuevo, reutiliza el existente") y habría dejado el login social más estricto que el password — inconsistencia que no le correspondía decidir a este ticket. `SocialLoginSuccessHandler` replica el comportamiento real de hoy (ninguno), documentado explícitamente en su Javadoc. **Reportado al Product Owner al cerrar el ticket** — candidato a ticket futuro si se quiere un gate de 2FA real, tocando ambos flujos a la vez.
- **9 tests `@WebMvcTest`/`@Import(SecurityConfig.class)`** (los mismos del ticket 036) necesitaron dos `@MockitoBean` nuevos (`SocialLoginSuccessHandler`, `SocialLoginFailureHandler`) — mismo motivo que el `ClientRegistrationRepository` mockeado desde el 036: `securityFilterChain` ya no puede construirse sin ellos en esa slice.
- **3 violaciones nuevas de Quality Gate encontradas y corregidas antes de reportar terminado:** `java:S1068` (campo `SEPARATOR` que quedó sin uso en `TenantAwareClientRegistrationRepository` tras delegar el parseo a `SocialRegistrationId`), `java:S7467` (variable de `catch` sin usar en `SocialLoginUserResolver`, resuelta con el mismo patrón `catch (... _)` ya usado en el proyecto), `java:S6068` (`eq(...)` redundante en un test sin ningún otro matcher mezclado).
- 308/308 tests en verde (285 previos + 23 nuevos: `SocialRegistrationIdTest`, `SocialLoginUserResolverTest`, `SocialLoginSuccessHandlerTest`, `SocialLoginFailureHandlerTest`).

## Ticket 040: `admin-identity-providers.html` muestra el `redirect_uri` a registrar

Nace de `docs/definiciones/login-social-real.md` (Diseño técnico, decisión 1) — consecuencia directa de que el `redirect_uri` sea único y compartido por todos los tenants. Ejecutado con delegación real al rol `frontend-dev`, en paralelo con 036/041.

- Bloque `redirect-uri-block` en las tarjetas de Google y Facebook, mismo valor en ambas, con `<input readonly>` + botón "Copiar" (`navigator.clipboard.writeText()`, sin dependencias externas — no existía patrón previo de copiar-al-portapapeles en el proyecto).
- `UiPagesController` arma el valor desde `app.base-url` (misma property ya usada por `VerificationLinkFactory`/`AuthorizationServerConfig` para URLs absolutas de esta app, sin property nueva) + la ruta fija `/login/oauth2/code/{registrationId}` que Spring `oauth2Login()` espera por convención — `{registrationId}` se deja literal a propósito, el admin registra la URL completa tal cual.
- **Sin tocar `TenantIdentityProviderService`** — confirmado, cambio puramente informativo en la UI (restricción heredada del ticket 014).
- **2 violaciones nuevas de Quality Gate (`java:S1075`, "hardcoded URI") encontradas y corregidas antes de mergear** — mismo patrón ya existente sin marcar en `VerificationLinkFactory`/`AuthorizationServerConfig` (el fallback local-dev de `app.base-url`) y la ruta fija de convención de Spring (correcta hardcodeada, sin parámetro del que extraerla) — ambas resueltas con `@SuppressWarnings` documentado, verificado corriendo Sonar localmente contra el servidor real antes de cada push para no repetir ciclos de CI fallidos.
- Verificado en vivo (Chrome): valor correcto en ambas tarjetas, clic real (gesto de usuario, no sintético) en "Copiar", contenido del portapapeles confirmado.
- 260/260 tests en verde (una aserción existente extendida, sin tests nuevos).

## Ticket 041: HU-5 — establecer password en una cuenta social-only

Nace de `docs/definiciones/login-social-real.md` (HU-5, agregada al alcance tras resolver OQ-5 con el Product Owner). Funcionalmente independiente del resto de la épica de login social (no toca `oauth2Login`/`ClientRegistrationRepository`/`external_identity`). Ejecutado con delegación real al rol `fullstack-dev`.

- **`POST /api/v1/account/password`**: `SetPasswordService` + `SetPasswordController`. Rechaza con `PasswordAlreadySetException` (409) si ya existe `password_hash` — nunca sobreescribe silenciosamente (colapsar "establecer por primera vez" y "cambiar" en un solo flujo era exactamente lo que el ticket pedía evitar).
- **Decisión de seguridad real, no solo seguir el ticket al pie de la letra:** el patrón ya existente en `/api/v1/2fa`/`/api/v1/change-email` (`userId` enviado por el cliente + header `X-Client-Id`, sin verificación de posesión — aceptable ahí porque completar esos flujos igual exige poseer el correo/SMS de la víctima, ver `TenantScopedUserResolver`) NO se copió aquí. Establecer una password surte efecto de inmediato y no tiene ese segundo factor — con ese patrón, cualquiera que adivinara el `userId` de una cuenta social-only la tomaría al instante. En su lugar, el endpoint usa el **Bearer JWT real** ya cableado en `SecurityConfig` (mismo mecanismo del panel admin): el `userId` sale del claim `sub` verificado, nunca del body. Sin infraestructura nueva — la ruta simplemente no se agregó a `permitAll`.
- Validación de fortaleza y hashing reutilizados tal cual (`PasswordPolicy`, mismo `PasswordEncoder` Argon2id que `RegistrationService`/`PasswordResetService`).
- `cuenta.html`: tarjeta "Establecer contraseña" visible solo si `!hasPassword` (campo nuevo en `UserResponse`, derivado, nunca expone el hash). Bug de scope de `snapshot` preexistente corregido de paso.
- Verificado en vivo con una cuenta social-only simulada (`password_hash = NULL` directo, ya que 038-040 no estaban mergeadas al momento de este ticket): tarjeta visible → submit → mensaje de éxito → tarjeta se oculta sin recargar → login posterior con el password nuevo confirmado real.
- **3 violaciones nuevas de Quality Gate (`java:S5778`) encontradas y corregidas antes de abrir el PR** — mismo patrón ya visto en el ticket 035 (lambdas de `assertThatThrownBy` con más de una invocación que podría lanzar).
- **Hallazgo de mejora continua, no bloqueante:** `/ui/cuenta` mezclaba dos modelos de confianza (userId del cliente vs. JWT real) sin ninguna guía explícita de cuándo usar cada uno — candidato a criterio escrito para que el próximo endpoint sensible de esa página no lo redescubra desde cero.

## Ticket 044: fix — `admin-identity-providers.html` mostraba el `redirect_uri` sin resolver

Hallazgo real encontrado al intentar registrar el `redirect_uri` de verdad en Google Cloud Console (paso operativo del ticket 043) — no un ejercicio hipotético. Google exige coincidencia **exacta** del `redirect_uri`, sin plantillas ni wildcards; el ticket 040 le mostraba al admin `{registrationId}` **literal, sin resolver**, que nunca iba a hacer match contra la request real. Ver la corrección completa en `docs/definiciones/login-social-real.md` (Decisión 1 / OQ-1, actualizada).

- `UiPagesController.adminIdentityProviders(...)` ahora resuelve el `IdentityClient` real del tenant (`clientContextResolver.resolveClient`, no solo `resolveTenant`) y arma **dos** valores concretos, uno por proveedor, usando el nuevo formateador `SocialRegistrationId.of(...).toString()` (inverso de `SocialRegistrationId.parse`, mismo formateador que deberá reutilizar el ticket 039 al construir los links de `/oauth2/authorization/**` — mismo `"::"`/provider-en-minúsculas en un solo lugar).
- `admin-identity-providers.html`: dos atributos (`oauth2RedirectUriGoogle`/`oauth2RedirectUriFacebook`), texto corregido ("específico de este cliente" en vez de "el mismo para todos los tenants").
- `docs/definiciones/login-social-real.md` corregido — no se cierra en silencio: la Decisión 1 original describía bien el patrón de ruta, pero la conclusión de "un valor compartido" para la UI era incorrecta.
- Test de `UiPagesControllerTest` actualizado: confirma los dos valores resueltos (con UUID real) y confirma explícitamente que `{registrationId}` ya NO aparece sin resolver en el HTML.
- 308/308 tests en verde. Quality Gate de SonarQube verificado en local antes del PR: OK.

## Ticket 043: pre-requisito operativo — confirmar/registrar credenciales reales de Google/Facebook

Nace de `docs/definiciones/login-social-real.md` (riesgo R-3) — acción operativa, no un ticket de producto. Ejecutado en parte por subagente (fixture QA + intento inicial de guardado) y en parte directamente por el orquestador, con autorización explícita del Product Owner para completar el paso final (bloqueo de permisos del subagente al leer `~/dev-infra/.env` para las credenciales de Vault) y para registrar el `redirect_uri` real en las consolas de Google/Meta vía Claude in Chrome.

- **Credenciales del ticket 006 confirmadas vigentes** — no hizo falta regenerarlas.
- **Google Cloud Console** (cliente `auth-core-mc-web`): `redirect_uri` real registrado — `http://localhost:8080/login/oauth2/code/22222222-2222-2222-2222-222222222222::google` (valor concreto por tenant, no la plantilla `{registrationId}` que el ticket 040 mostraba originalmente). **Hallazgo real que disparó el ticket 044**: Google exige coincidencia exacta, sin wildcards — el criterio original de este ticket (redirect_uri "único, compartido por todos los tenants") era incorrecto; corregido en `docs/definiciones/login-social-real.md` y en la UI del panel admin.
- **Meta for Developers** (app "Auth Core MC"): sin acción necesaria mientras la app siga en Modo de desarrollo — `http://localhost` se permite automáticamente, confirmado vía la propia UI de Meta. Pendiente registrar el valor explícito si la app pasa a producción/dominio real.
- **Credenciales reales guardadas vía la API real** (`PUT /api/v1/admin/identity-providers/GOOGLE`, nunca `UPDATE` SQL directo) para el tenant Acme, con un usuario `PLATFORM_ADMIN` de prueba reutilizable (`qa-oauth-043@example.com`) — mismo patrón ya usado en tickets anteriores (031-034) para fixtures de QA. Cifrado verificado contra la BD real: `client_secret_encrypted` es ciphertext Vault Transit, nunca texto plano.
- **Flujo real verificado de punta a punta hasta donde es posible sin una cuenta de Google de prueba real**: `GET /oauth2/authorization/{identityClientId}::google` devuelve `302` a `accounts.google.com` con el `client_id` y `redirect_uri` reales correctos — confirma que `TenantAwareClientRegistrationRepository` (ticket 036) resuelve credenciales reales end-to-end.
- **Nota de proceso:** el subagente que ejecutó la mayor parte del ticket se detuvo correctamente al no poder leer `~/dev-infra/.env` (bloqueado por su clasificador de permisos) en vez de rodear el bloqueo — el orquestador declinó pasarle las credenciales directamente (para no incurrir en "cross-session permission laundering") y escaló al Product Owner, quien autorizó completar ese último paso directamente.

## Ticket 038: endpoint `POST /api/v1/oauth2/social-exchange`

Nace de `docs/definiciones/login-social-real.md` (Diseño técnico, decisión 5) — último paso del backend de la épica: canjea el código de un solo uso que `SocialLoginSuccessHandler` (ticket 037) emitió por tokens reales.

- **`SocialExchangeController`** (`POST /api/v1/oauth2/social-exchange`, header `X-Client-Id` + body `{ code }`): consume el código vía `RedisTokenStore.consume(EXCHANGE_PURPOSE, code)` (de un solo uso real), resuelve el `User` y llama a `DirectTokenService.issueTokens(client, user)` — **el mismo minter que `/api/v1/login`, sin tocar su firma**. Respuesta `200` con el mismo shape `LoginResponse { user, tokens }` — reutiliza la clase existente, sin DTO nuevo, así `AuthCoreUi.saveSession(...)` no necesita lógica distinta para login social vs. password.
- **`X-Client-Id` requerido, mismo patrón que `/api/v1/login`:** el código solo lleva el `userId` — el minter necesita además un `IdentityClient` first-party. El mismo `client_id` ya viaja en la query del redirect a `/ui/social-callback`, y `AuthCoreUi.call(...)` ya lo adjunta automáticamente como header (mecanismo existente, ninguno nuevo). Cliente no first-party → `403` **sin consumir el código** (mismo orden que `AuthController`).
- **Verificación cruzada de tenant defensiva** (no alcanzada por el flujo real, ya que el mismo `IdentityClient` resuelve redirect y canje): usuario resuelto de un tenant distinto al del cliente resuelto por `X-Client-Id` → mismo error genérico `invalid_token` que un código inválido/expirado/ya usado — los cuatro casos deliberadamente indistinguibles desde afuera.
- **`EXCHANGE_PURPOSE` de `SocialLoginSuccessHandler` ensanchado a `public`** para que el controller (en otro paquete) lo reutilice sin duplicar el literal — una sola fuente de verdad. Endpoint agregado a `permitAll` en `SecurityConfig` (es el paso que otorga la sesión, no uno que la requiere).
- **11 tests nuevos**: 8 `@WebMvcTest` (mocks) + 3 end-to-end con Testcontainers reales (Redis, DB, `DirectTokenService` real) que prueban la garantía real de un solo uso, no solo el mock. 319/319 tests del proyecto en verde. Quality Gate de SonarQube verificado en local antes del PR: `OK` (0 violaciones nuevas, 91.8% cobertura nueva).
- **Sin Postman** — confirmado que el proyecto no tiene ninguna colección, mismo estado que dejaron 036/037.
- **Hallazgo de proceso, no de producto:** el ticket llegó implementado por un subagente pero sin commitear — al intentar commitear en su worktree, `main-branch-guard.sh` (hook que bloquea commits directos a `main`) resultó ser **worktree-unaware**: siempre evalúa la rama del worktree principal (que en ese momento estaba en `main`, tras cerrar el ticket 043), ignorando el `cd <worktree> &&` real del comando — un falso bloqueo de trabajo legítimo en un worktree secundario. Corregido en el propio hook (`~/.claude/hooks/main-branch-guard.sh`, fuera de este repo) para extraer un `cd <dir>` inicial del comando y evaluar la rama de ese directorio en vez de la del proceso — verificado con pipe-tests antes y después del fix, con autorización explícita del Product Owner para editar el hook.

## Ticket 039: botones de login social + página de canje + página de error

Nace de `docs/definiciones/login-social-real.md` (HU-1, HU-3, HU-4, HU-9) — último ticket de UI de la épica de login social real, cierra el flujo que 036/037/038 dejaron listo del lado del servidor. Ejecutado con delegación real al rol `fullstack-dev`.

- **`UiPagesController#login`/`#register`**: además de `theme(...)`, ahora llaman a `addSocialLoginAttributes(...)`, que resuelve el `IdentityClient` real (`clientContextResolver.resolveClient`, mismo patrón ya aceptado en `adminIdentityProviders`/`oauth2RedirectUri` de doble lookup) y `TenantIdentityProviderService.list(tenant)` para armar `googleEnabled`/`facebookEnabled` (booleanos) y `googleAuthorizationUrl`/`facebookAuthorizationUrl` (`/oauth2/authorization/{identityClientId}::{provider}`, formateados con `SocialRegistrationId.of(...)` — mismo formateador que ticket `044` ya usa, ningún formato nuevo). Un proveedor deshabilitado nunca renderiza su botón — nunca un clic muerto contra el fail-closed de Decisión 4.
- **Dos rutas nuevas**: `GET /ui/social-callback` (themed — SÍ hay `client_id` resoluble aquí, a diferencia de las páginas de confirmación por token) y `GET /ui/social-login-error` (sin `client_id`, sin theming, mismo patrón que `verify-email-confirm.html`/`change-email-confirm.html`) — ambas ya tenían su contrato fijado desde el ticket `037` (`SocialLoginSuccessHandler`/`SocialLoginFailureHandler` ya redirigían ahí).
- **`social-callback.html`**: mismo patrón "confirma automáticamente al cargar" que `verify-email-confirm.html`, pero llamando a `POST /api/v1/oauth2/social-exchange` con el `code` de la query — `AuthCoreUi.call(...)` ya adjunta `X-Client-Id` leyendo `?client_id=` de la misma URL, ningún mecanismo nuevo. Éxito: `AuthCoreUi.saveSession(result.user, result.tokens)` + redirect a `/ui/cuenta`. Verificado en vivo insertando un código real en Redis con el mismo formato de clave que `RedisTokenStore`/`SocialLoginSuccessHandler.EXCHANGE_PURPOSE` (sin cuenta de Google real disponible en este entorno) — el canje real contra `/api/v1/oauth2/social-exchange` funcionó de punta a punta, sesión reflejada en `/ui/cuenta`; un código reusado mostró el error genérico esperado (`"The exchange code is invalid, expired, or already used"`).
- **`social-login-error.html`**: sin theming (sin `appName`/`--primary-color`, usa los defaults de `app.css`), mensaje claro. **Corregido al revisar el ticket**: la primera versión enlazaba "Volver al inicio de sesión" a `/ui/login` sin `client_id` — como esta página nunca tiene un `client_id` recuperable (Decisión 4 lo impide a propósito), ese link garantizaba un `400` al hacer clic (mismo comportamiento ya aceptado en HU-4, pero un link que siempre falla es peor que ninguno). Reemplazado por texto que guía sin prometer un destino roto: vuelve a la aplicación/sitio donde comenzó el login — sin ningún enlace.
- **`login.html`** también lee `?error=social_login_cancelled` (que `SocialLoginFailureHandler`, ticket 037, ya emitía desde antes de que existiera esta página) y llama al mismo `showStatus()` que cualquier otro error de la página — cierra el placeholder que el Javadoc de `SocialLoginFailureHandler` dejó marcado explícitamente para este ticket.
- **CSS nuevo** (`app.css`): `.social-login`/`.button-social` (un `<a>` estilado como `button.secondary` — navega a `/oauth2/authorization/**`, no envía un formulario) y `.divider` ("o" entre el formulario de password y los botones sociales). Los logos `logo-google`/`logo-facebook` (ticket 024) se reutilizan tal cual, sin rediseño.
- **Verificado en vivo (Chrome)** contra el tenant real `acme-local-dev` (Google `enabled` desde el ticket `043`): botón de Google visible en `login.html`/`register.html`; con un tenant sin ningún proveedor configurado (`ticket041-live-check`), ningún botón ni divisor se renderiza; canje exitoso de código real reflejado en `/ui/cuenta`; código reusado muestra el error genérico; `?error=social_login_cancelled` muestra el mensaje themed correcto; `/ui/social-login-error` visualmente correcta y sin ninguna referencia de marca del tenant.
- **6 tests nuevos en `UiPagesControllerTest`** (visibilidad de cada botón según `enabled`, proveedor deshabilitado no muestra botón, rutas nuevas con/sin `client_id`) — **3 tests preexistentes consolidados en uno parametrizado** (`@ParameterizedTest`) por un hallazgo real de Quality Gate (`java:S5976`, "Replace these 3 tests with a single Parameterized one") disparado al agregar el test de `/ui/social-callback` junto a los de `/ui/cuenta`/`/ui/password-reset/request`, que ya compartían la misma forma. 325/325 tests en verde (319 previos + 6 nuevos). Quality Gate de SonarQube verificado en local antes del PR: `OK` (0 violaciones nuevas, 92% cobertura nueva).
- **Sin cambios de contrato de API** — `POST /api/v1/oauth2/social-exchange` (ticket 038) se consume tal cual, sin DTO ni endpoint nuevo; `docs/API.md`/Postman sin cambios.

## Ticket 045: 2FA obligatorio real (login con password y login social)

Cierra el hueco que quedó explícitamente flagged en el ticket `037` (OQ-8, ver `docs/API.md`): ni `/api/v1/login` (password) ni `/api/v1/oauth2/social-exchange` (social) exigían nunca el segundo factor de un usuario que lo tenía activo — ambos emitían tokens de inmediato, sin importar `User.getTwoFactorMethod()`.

- **`LoginCompletionService` (paquete `service`), el punto de unificación real** — inyectado en `AuthController` y `SocialExchangeController` **en vez de** que cada uno llame a `DirectTokenService` directamente, exactamente lo que evita las dos implementaciones paralelas del gate. Un solo método, `complete(IdentityClient, User)`, devuelve `LoginCompletionResult` (record con campos nullable + `completed(...)`/`twoFactorRequired(...)` como únicos constructores — no un `sealed interface`, para no introducir un patrón nuevo en un codebase que hasta ahora resuelve esto con records simples): si `user.getTwoFactorMethod() == NONE`, mintea tokens ya mismo vía `DirectTokenService` (comportamiento sin cambios); si no, no mintea nada — emite un `pendingToken` de un solo uso vía `RedisTokenStore` (mismo mecanismo que ya usa el código de canje social de `SocialLoginSuccessHandler`, TTL 5 minutos, `purpose="login-2fa-pending"`), cuyo valor empaqueta `clientId + "::" + userId` (mismo estilo "empaqueta y separa al leer" que ya usa `EmailChangeService`).
- **Envío automático del código OTP en el propio gate:** si el método es `OTP_EMAIL`/`OTP_SMS`, `LoginCompletionService` llama `OtpService.requestOtp(user)` en el momento de emitir el `pendingToken` — decisión necesaria porque la respuesta `202` deliberadamente no lleva `userId` (solo `pendingToken` + `method`), así que el cliente no tiene forma de invocar `/2fa/otp/request` por separado, y todavía no existe ninguna UI que orqueste un paso de "enviar código" explícito. Una colisión con el cooldown de reenvío (`TooManyAttemptsException`) se traga silenciosamente — no es una falla de este intento de login, el código previo sigue siendo válido. `TOTP` no necesita ningún envío (el código ya vive en la app autenticadora).
- **Endpoint nuevo compartido, `POST /api/v1/login/2fa-verify`** (`TwoFactorLoginController`, público en `SecurityConfig` — es el paso que otorga la sesión, no uno que la requiere, mismo criterio que `/social-exchange`): consume el `pendingToken` vía `RedisTokenStore.consume` (un solo uso real), confirma que el `clientId` empaquetado coincide con `X-Client-Id` (error genérico `invalid_token` si no, mismo criterio defensivo que la verificación cruzada de tenant de `SocialExchangeController`), y verifica el código reutilizando **tal cual** `TotpService.verify`/`OtpService.verifyOtp` — incluyendo el rate-limiting que `OtpService` ya trae vía `LoginRateLimiter`, nada duplicado aquí. En éxito, mintea tokens llamando a `DirectTokenService` **directamente, no a `LoginCompletionService` de nuevo** (la preferencia de 2FA del usuario no se desactiva por una verificación exitosa — volver a pasar por `LoginCompletionService` reemitiría otro `pendingToken` en bucle en vez de tokens).
- **`AuthController`/`SocialExchangeController` refactorizados**: ya no dependen de `DirectTokenService`, solo de `LoginCompletionService`. Ambos devuelven `ResponseEntity<Object>`: `200` + `LoginResponse` de siempre, o `202` + `TwoFactorRequiredResponse { twoFactorRequired: true, pendingToken, method }` — `202` elegido (no solo el campo discriminador) para que el status code por sí solo ya distinga los dos casos.
- **Decisión sobre `LoginEventRecorder` (pedida explícitamente por el ticket, no asumida en silencio):** `LoginOutcome` es un `CHECK` de dos valores a nivel de esquema (`V5__login_event.sql`) — agregar un tercer estado "pendiente de 2FA" es un cambio de esquema que necesita su propio VoBo dedicado (regla del equipo sobre cambios que rompen compatibilidad), no algo para meter de paso aquí. `AuthController` sigue registrando `SUCCESS` exactamente en el mismo punto que antes (password verificado), sin esperar la decisión de `LoginCompletionService` — mismo precedente que ya sentó `SocialLoginSuccessHandler`, que registra éxito en "identidad probada" antes de su propio paso de canje, que puede fallar por separado sin que eso borre el evento ya registrado. Consecuencia real, no oculta: un segundo factor incorrecto o abandonado no genera ningún evento nuevo — haría falta un tercer `LoginOutcome` para atribuir correctamente ese desenlace, lo cual es su propio ticket de cambio de esquema (también tocaría el dashboard de métricas del ticket `028`).
- **Hallazgo de seguridad, no resuelto aquí, cerrado por el ticket `047`:** `TotpService.verify` nunca tuvo su propio límite de intentos (a diferencia de `OtpService.verifyOtp`, que sí reutiliza `LoginRateLimiter`) — hasta este ticket eso vivía únicamente detrás del uso autoservicio de `/ui/cuenta`; ahora queda expuesto, por primera vez, como parte del flujo principal de login. Flagged para decisión del Product Owner, no resuelto silenciosamente dentro de este ticket — decidido reutilizar `LoginRateLimiter` (mismo mecanismo que `OtpService`), cerrado en el ticket `047`.
- **Gap de UI, explícitamente fuera de alcance:** el ticket es backend-únicamente a propósito — no toca `/ui/login`. Un usuario real con 2FA activo que intente loguearse desde ahí hoy queda con la respuesta `202 twoFactorRequired` sin ninguna pantalla que la interprete. Decidido con el Product Owner que esto NO bloquea el merge de este ticket (riesgo aceptado hasta cerrar el seguimiento) — trackeado en el ticket `046`, que también agrega reenvío de OTP y manejo de expiración del `pendingToken`.
- **23 tests nuevos** (342/342 del proyecto en verde): `LoginCompletionServiceTest` (7, unitarios con mocks), `TwoFactorLoginControllerTest` (8, `@WebMvcTest`), `TwoFactorLoginEndToEndTest` (6, Testcontainers reales — Redis, DB, `TotpService`/`OtpService`/`DirectTokenService` reales, sin mocks salvo `EmailSender` — prueba la garantía real de un solo uso del `pendingToken`, el rechazo por `X-Client-Id` cruzado, y el round-trip completo para TOTP vía password y OTP vía social), más un test nuevo en cada uno de `AuthControllerTest`/`SocialExchangeControllerTest` para el caso `202`. **9 issues nuevos reales encontrados por el Quality Gate al abrir el PR real (#55), corregidos antes de mergear** — no detectados por una verificación local previa a la apertura del PR (import sin usar, 2 wildcards genéricos `ResponseEntity<?>`→`ResponseEntity<Object>`, un if/else reemplazable por switch expression, y 4 en tests: `eq(...)` redundante + import estático de `doThrow` faltante). Cobertura nueva 92.1%, 0% duplicación, ambas dentro del umbral.
- **Sin Postman** — el proyecto sigue sin ninguna colección (mismo estado que dejaron 036/037/038).

## Ticket 042: verificación — métricas y auditoría con proveedores sociales reales

Nace de `docs/definiciones/login-social-real.md` (OQ-10). Ticket de **verificación**, no de construcción — sin ningún cambio de código, sin tests nuevos.

- **Verificado en vivo con datos 100% reales, nunca simulados**: login social real exitoso con GOOGLE, login social real exitoso con FACEBOOK, y login social real fallido con GOOGLE (cancelación real de consentimiento, con una segunda cuenta real que aún no había autorizado la app — para forzar la pantalla de consentimiento en vez de un login silencioso ya autorizado). Los tres casos registraron `login_event` correctamente (`provider`/`outcome` reales, `user_id` nulo en el fallo — mismo criterio ya documentado en 037/045: el evento se registra en "identidad probada", y una negación de consentimiento nunca llega a probar identidad) y, en éxito, `external_identity` vinculado con el `provider_user_id` real de cada proveedor.
- **`/ui/admin/metrics` (ticket 028) confirmado en vivo por el Product Owner, dos veces**: la dona de éxito/fallo y la barra de actividad por proveedor reflejan los cuatro eventos reales sin ningún cambio en `charts.js`/`admin-metrics.html` — el diseño del ticket 028 (agnóstico al valor de `provider`, texto libre desde el ticket 015) funciona exactamente como se esperaba con proveedores sociales reales, no solo `PASSWORD`.
- **Hallazgo de proceso, resuelto en el camino, no oculto**: `tenant_identity_provider` para el tenant Acme solo tenía `GOOGLE enabled=true` al empezar esta verificación — Facebook nunca se había guardado de verdad vía `PUT /api/v1/admin/identity-providers/FACEBOOK`, pese a que el ticket 043 tituló su cierre "confirma credenciales reales de Google/Facebook" (en realidad solo confirmó la consola de Meta y el `redirect_uri`, nunca persistió el secreto en la app). Resuelto por el propio Product Owner desde `/ui/admin/identity-providers` durante esta misma verificación — no ameritó ticket aparte.
- **Hallazgo de infra, resuelto en esta misma sesión**: `bootRun` sin `VAULT_ADDR`/`VAULT_ROOT_TOKEN` en su entorno causa un `500` real al iniciar cualquier flujo OAuth2 (no puede desencriptar el secreto del tenant); por separado, Vault amanece sellado tras cada reinicio de su contenedor (`Vault is sealed`, otro `500`) — ambos ya conocidos y documentados por el propio proyecto (ver `docs/README.md` y `dev-infra/scripts/vault-unseal.sh`, ticket 017), pero fáciles de repetir si se olvida el paso. Sin cambios de código; documentado aquí como recordatorio para el próximo ticket que necesite levantar la app desde cero.

## Ticket 046: UI de `/ui/login`/`/ui/social-callback` para el 2FA obligatorio real

Cierra el gap de UI que el ticket 045 dejó explícitamente flagged: `/api/v1/login` y `/api/v1/oauth2/social-exchange` responden `202 twoFactorRequired` para un usuario con 2FA activo, pero ninguna pantalla lo interpretaba — un usuario real quedaba varado.

- **`fragments/two-factor-step.html`** (nuevo), un solo fragmento compartido entre `login.html` (password) y `social-callback.html` (social) — mismo criterio "markup server-side, comportamiento en JS" que el resto del proyecto (ver `icons.html`). Icono `icon-2fa` (ticket 032, ya existente), hint según el método, campo de código, botón "Verificar", botón "Reenviar código" (oculto por defecto) y mensaje de expiración (oculto por defecto).
- **`AuthCoreUi.startTwoFactorStep(pendingToken, method, statusEl, onVerified)`** (`api.js`, nuevo) — único punto de cableado para ambos flujos de entrada, ninguna lógica duplicada. Revela el fragmento, arma el hint, muestra "Reenviar código" solo para `OTP_EMAIL`/`OTP_SMS` (nunca `TOTP`, cuyo código ya vive en la app autenticadora), y cablea submit/resend contra los endpoints reales.
- **`POST /api/v1/login/2fa-resend`** (`TwoFactorLoginController`, nuevo, ver `docs/API.md`): usa `RedisTokenStore.peek` (método nuevo, junto a `issue`/`consume`) — nunca `consume`, para no invalidar el `pendingToken` que el usuario todavía necesita para el verify real. No-op para `TOTP`. A diferencia del envío automático de `LoginCompletionService` (que traga una colisión de cooldown), aquí el cooldown real de `OtpService` **sí** se surface como `429` — el usuario pidió este reenvío explícitamente.
- **Expiración proactiva del lado del cliente**: `setTimeout` de 5 minutos (misma constante que `LoginCompletionService.PENDING_2FA_TTL`, documentada en `api.js` para no desincronizarse en silencio) deshabilita el formulario antes de que un submit falle contra un token ya vencido — la expiración real la sigue imponiendo el TTL de Redis del lado del servidor.
- **Hallazgo real, encontrado probando en vivo (no solo supuesto), corregido en el propio ticket**: `TwoFactorLoginController.verify` consume el `pendingToken` **antes** de validar el código (diseño ya existente del ticket 045, no tocado aquí) — un intento fallido (código incorrecto o rate-limit) invalida el token exactamente igual que si hubiera expirado. La primera versión de esta UI dejaba el formulario abierto invitando a un reintento condenado a fallar con un error todavía más confuso. Corregido: **cualquier** fallo de `/2fa-verify` transiciona al mismo estado terminal (formulario deshabilitado + "vuelve a iniciar sesión"), unificado en una sola función `invalidate()` — no dos mecanismos paralelos para la misma consecuencia real.
- **Verificado en vivo (Chrome), ambos flujos de entrada, de punta a punta con datos reales** — usuario de prueba reutilizable `qa-2fa-046@example.com` (TOTP activo): login con password + código correcto → sesión guardada → `/ui/cuenta`; código incorrecto → error + formulario invalidado (hallazgo de arriba); mismo resultado repitiendo con un código de canje social real insertado en Redis (mismo patrón de verificación en vivo que ya usó el ticket 039) contra `/ui/social-callback`.
- **Gap de entorno local, no de la app**: el flujo `OTP_EMAIL`/`OTP_SMS` no se pudo verificar visualmente aquí porque `RESEND_API_KEY` no está configurado en este entorno de desarrollo (mismo tipo de gap ya visto con Vault en el ticket 042) — cubierto en cambio por `TwoFactorLoginEndToEndTest`/`TwoFactorLoginControllerTest`, reales salvo el `EmailSender` mockeado.
- **10 tests nuevos, 358/358 del proyecto en verde**: `RedisTokenStoreTest` (+2, `peek`), `TwoFactorLoginControllerTest` (+5, `@WebMvcTest` del resend), `TwoFactorLoginEndToEndTest` (+3, Testcontainers reales).
- **Sin Postman** — el proyecto sigue sin ninguna colección.

## Ticket 047: rate-limiting real en `TotpService.verify`

Cierra el hallazgo de seguridad que el ticket 045 dejó explícitamente flagged: `TotpService.verify` nunca tuvo su propio límite de intentos — un código TOTP de 6 dígitos (1,000,000 valores posibles) era adivinable sin ninguna mitigación, expuesto por primera vez en el flujo principal de login desde que `POST /api/v1/login/2fa-verify` (ticket 045) lo llama directamente.

- **Reutiliza `LoginRateLimiter` tal cual** — mismo mecanismo que ya usa `OtpService.verifyOtp`, sin lógica nueva ni componente paralelo. Namespace de intentos propio (`"totp:" + userId`, distinto de `"otp:" + userId`) — un usuario con historial en ambos métodos (p. ej. tras cambiar su preferencia) tiene dos contadores independientes, cada guess-surface con su propio límite.
- **`checkAllowed` antes de cualquier otra cosa**, igual que `OtpService`: un usuario ya bloqueado ni siquiera llega a desencriptar el secreto. `recordFailure` en los dos casos de rechazo reales (código fuera de ventana, código ya usado); `recordSuccess` (resetea el contador) solo en verificación exitosa.
- **La comprobación de "no enrolado" queda fuera del rate-limit a propósito** — no es parte de la superficie de adivinanza real (solo ocurre por estado tamperado/inconsistente, nunca desde un flujo real: un usuario sin secreto enrolado nunca llega a este método con `TwoFactorMethod.TOTP` activo).
- **Mismo comportamiento en ambos puntos de entrada** — autoservicio (`/ui/cuenta`, ticket 005) y el flujo principal de login (`/api/v1/login/2fa-verify`, ticket 045/046) comparten el único `TotpService.verify`, ningún gate duplicado.
- **2 tests nuevos** (`TotpServiceTest`): bloqueo real tras 5 intentos fallidos, y que una verificación exitosa resetea el contador (no se acumula across éxito/fallo).
- **Sin Postman** — el proyecto sigue sin ninguna colección.

## Ticket 048: grant `client_credentials` para clientes machine-to-machine

Pedido externo: `mail-core-mc` (nuevo servicio del ecosistema) necesitaba autenticarse app-a-app, sin usuario humano — algo que este servicio no soportaba. Ya estaba anotado como "extensión futura" en `docs/BASE_DE_DATOS.md` desde el ticket `007` ("parametrizar scope/grants por cliente"); este ticket lo resuelve.

- **Migración `V9` puramente aditiva**: `identity_client.is_machine_client` (default `false`) y `.scopes` (default `{openid,profile}`) — todo cliente existente conserva exactamente el comportamiento hardcodeado de antes, sin migrar datos ni tocar filas.
- **`IdentityClient` gana un constructor, no reemplaza el que ya existía**: el de 5 args (usado en ~35 call sites, casi todos tests) sigue igual, delegando al nuevo de 7 args con los defaults — evitó una migración masiva de tests por un cambio que, en esencia, solo afecta a un tipo nuevo de cliente que todavía no existía.
- **`TenantAwareRegisteredClientRepository` bifurca por `isMachineClient()`**: `CLIENT_CREDENTIALS` únicamente (no tiene sentido ofrecer Authorization Code a un cliente sin usuario), scopes desde la columna nueva en vez de los hardcodeados `openid`/`profile` — esto último aplica también a clientes normales ahora (mismo valor por default, pero ya no atado al código).
- **Sin proveedor/config nuevo para el grant en sí**: `OAuth2AuthorizationServerConfigurer` ya trae soporte de `client_credentials` por defecto (`OAuth2ClientCredentialsAuthenticationProvider`) en cuanto el `RegisteredClient` declara ese grant — el trabajo real era de dónde sale el `RegisteredClient`, no de wiring de Spring Security nuevo.
- **Clientes m2m cuelgan de un tenant "Plataforma" dedicado, no de uno de negocio**: `identity_client.tenant_id` sigue `NOT NULL` (no se relajó el esquema) y los TTLs de token siguen viniendo del tenant — crear un tenant "Plataforma (clientes m2m)" reusa ese mecanismo tal cual, en vez de construir una ruta de configuración de TTL paralela solo para clientes m2m.
- **El secreto real se generó, hasheó con el mismo `Argon2PasswordEncoder` de la app, y solo su hash tocó la base de datos** — nunca el secreto en claro, ni en un commit, ni en un log de esta sesión.
- **Verificado en vivo, no solo con mocks**: `POST /oauth2/token` con `grant_type=client_credentials` y las credenciales reales de `mail-core-mc` devolvió un access token real, con `sub`/`scope` correctos y `kid` verificable contra `/oauth2/jwks`. Pedir un scope no autorizado (`openid`, no registrado para este cliente) respondió `400 invalid_scope`; un secreto incorrecto no emitió token.
- **Hallazgo de infra encontrado en el camino, no introducido por este ticket**: Testcontainers no podía hablar con Docker localmente — `/var/run/docker.sock` es un symlink roto (apunta al socket de una instalación vieja de Docker Desktop que ya no existe); el socket real de OrbStack vive en otra ruta. **Cualquier test con Testcontainers de este proyecto corrido localmente necesita `DOCKER_HOST=unix:///Users/marcocortes/.orbstack/run/docker.sock` explícito** hasta que alguien arregle el symlink (requiere `sudo`, fuera de alcance de este ticket). El self-hosted runner de CI no está afectado — confirmado, no asumido: `~/actions-runner-auth-core-mc/.env` ya trae `DOCKER_HOST` apuntando al socket correcto de OrbStack.
- **Sin Postman** — el proyecto sigue sin ninguna colección.

## Ticket 049: pipeline de CI/CD a la VM dedicada — EN CURSO (pivote a Jenkins, ver sección al final)

**Objetivo**: pasar de "CI solo en la Mac" a un despliegue real en la VM OCI
Ampere A1.Flex compartida (`ampere-free`, 159.54.153.37, Ubuntu 24.04 ARM64
— ver `~/.ssh/config`), con ambientes aislados en Docker Compose. Ticket
gemelo en `mail-core-mc` (011) reutiliza la misma VM y las mismas
convenciones documentadas aquí — **no vuelvas a derivarlas.**

Este ticket pasó por DOS diseños reales, no uno. Se documenta la historia
completa a propósito (regla del equipo: no silenciar un cambio de
dirección) — el diseño **vigente hoy es la sección "Diseño vigente
(rediseño dev/qa/prod)"**; la sección "Diseño original (histórico,
reemplazado)" se conserva como registro de qué se intentó, qué bloqueos
reales aparecieron y por qué se abandonó, no como algo a mantener.

### Diseño original (histórico, reemplazado 2026-08-30)

Primera versión: dos ramas persistentes, `integracion` (ambiente TEST) →
`main` (ambiente PROD, gate manual). Migración a la organización GitHub
`64bitstudio` a mitad de este ticket (repos públicos, runner self-hosted a
nivel de organización).

**Bloqueos reales encontrados y su resolución, en orden:**

1. `runs-on: self-hosted` a secas resultó ambiguo en cuanto existió más de
   un runner self-hosted visible para el repo (el de la Mac, repo-level, y
   `vm-oci-runner`, org-level) — todo runner self-hosted trae también la
   etiqueta genérica `self-hosted`, y GitHub empareja por subconjunto de
   labels, no por runner exacto. Un run real lo probó: `build-test-analyze`
   aterrizó en `vm-oci-runner` y falló (SonarQube en `localhost:9000` y
   `~/dev-infra/scripts/notify.sh` solo existían en la Mac). Fix aplicado
   entonces: `runs-on: [self-hosted, macOS]` (label exclusivo de la Mac,
   confirmado vía API, no asumido).
2. El paso de diagnóstico `docker compose ... ps` (tras el deploy real, con
   `if: always()`) fallaba con `required variable DB_PASSWORD/IMAGE_TAG is
   missing a value` — no llevaba `--env-file`, a diferencia del paso
   `up -d` de al lado. El job quedaba en rojo pese a que el deploy real ya
   había funcionado (contenedores healthy, healthcheck real en verde).
3. Con `--env-file` agregado, el mismo paso de diagnóstico seguía fallando
   solo para `IMAGE_TAG` — esa variable se define como env var de PASO
   (`env: IMAGE_TAG: ...` en el step "Deploy al stack ..."), y GitHub
   Actions no la hereda al step siguiente; nunca vivía en el `.env.*` en
   disco (por diseño: la asigna el workflow, no un valor fijo). Fix:
   repetir el mismo `env: IMAGE_TAG: ...` en cada paso que invoque
   `docker compose` contra ese archivo.
4. El diseño pedía un GitHub Environment `prod` con "required reviewers"
   para pausar `promote-prod` hasta aprobación manual. La API lo rechazó
   primero con `422` (billing plan no lo soporta en repo privado); tras
   migrar el repo a público SÍ se pudo configurar — pero en el primer
   `promote-prod` real (disparado manualmente por Marco vía
   `workflow_dispatch`, el gate interino mientras tanto), el job **no se
   pausó**: desplegó a PROD de verdad sin pedir aprobación. Causa
   confirmada vía API: `can_admins_bypass: true` en el Environment (valor
   fijo de GitHub, no configurable) — cualquier admin del repositorio salta
   el gate de reviewer automáticamente, y Marco es admin. El mecanismo no
   protegía nada real para un equipo donde el único humano con acceso de
   escritura es también admin.

Este último punto (no un bug de configuración, sino una limitación real de
la plataforma para equipos de una sola persona) fue la razón directa del
rediseño: Marco vio que el modelo generaba fricción manual real (2 PRs +
2 merges + 1 `workflow_dispatch` por cada cambio, incluyendo arreglar un
typo de CI) a cambio de una protección que no protegía nada. Ver el
rediseño abajo.

### Diseño vigente (rediseño dev/qa/prod, decisión de Marco, 2026-08-30)

**Flujo de ramas**: `feature/NNN` → PR → `dev` (merge **automático** en
cuanto el pipeline completo queda verde) → `qa` (merge **siempre manual**
de Marco) → `prod` (merge **siempre manual** de Marco). `main`/`integracion`
se renombraron a `prod`/`dev` respectivamente vía la API de rename de
GitHub (preserva PRs abiertos y branch protection automáticamente); `qa`
es una rama nueva, creada desde la punta de `prod` en el momento del
rediseño. Los tres tienen el mismo branch protection: PR obligatorio +
status check `build-test-analyze`, `required_approving_review_count: 0`
(self-merge permitido, igual que siempre).

**Sin GitHub Environment** — se retiró por completo (no `environment: qa`
ni `environment: prod` en ningún job). No protegía nada real (ver el punto
4 de arriba); el gate real ahora es literalmente que Marco tenga que hacer
el merge a `qa`/`prod` con sus propias manos. `dev` en cambio SÍ se
automatiza: en cuanto `build-test-analyze` + el resto del pipeline de `dev`
queda verde, el PR de `feature/NNN` se auto-mergea solo (ver "Bloqueos de
esta fase" — `allow_auto_merge` del repo quedó pendiente de que Marco lo
habilite, el clasificador de permisos del harness lo bloqueó).

**Cada etapa promueve, nunca reconstruye**: mismo mecanismo que el diseño
original (resolver el SHA real vía el label
`org.opencontainers.image.revision` del tag `:current` de la etapa
anterior — `auth-core-mc-dev:current` para promover a QA,
`auth-core-mc-qa:current` para promover a PROD), por la misma razón (la
estrategia de merge de GitHub puede dejar un SHA distinto en la rama
destino al que realmente se construyó).

**SonarQube se muda de la Mac a la VM** (`deploy/vm-infra/sonarqube/`,
instancia NUEVA — decisión explícita de Marco de no migrar historial, más
simple). Consecuencia directa: **el runner self-hosted de la Mac se retira
por completo** — la Mac queda dedicada solo a codificar/commits/push, todo
el pipeline (tests, Sonar, build, deploy) corre en el runner de la VM
(`vm-oci`). Esto además resuelve de raíz el bloqueo #1 del diseño anterior
(la ambigüedad de labels): con un solo runner self-hosted visible para el
repo, `runs-on: [self-hosted, vm-oci]` ya no es ambiguo, y
`runs-on: [self-hosted, macOS]` deja de tener sentido.

**Bootstrap circular real, encontrado y resuelto en el primer intento del
rediseño**: el job que levanta SonarQube en la VM (`sync-vm-infra`) tenía
originalmente `needs: build-test-analyze` — pero `build-test-analyze`
(ahora corriendo en la VM) necesita que SonarQube YA esté arriba para
poder analizar. Con esa dependencia, SonarQube nunca podía levantarse la
primera vez. Fix: `sync-vm-infra` corre en TODO push, sin depender de
ningún otro job — es un prerrequisito, no una consecuencia.

**Ambientes**: DEV y QA corren "bajo demanda" (`deploy/env-ctl.sh
<dev|qa> <up|down|status>` — la VM es de capa gratuita, 2 OCPU/12GB
compartidos con Postfix de mail-core-mc, SonarQube, Traefik y el runner).
PROD siempre activo. El propio pipeline hace `docker compose up -d` en
cada deploy (idempotente — los levanta si estaban parados), así que
"bajo demanda" solo afecta cuánto tiempo quedan corriendo ENTRE deploys,
no bloquea ningún deploy.

**Retención (`deploy/cleanup.sh`)**: DEV conserva 1 imagen de release, QA
conserva 1, PROD conserva 2 (rollback manual). Mismo mecanismo que antes
(borra por nombre de repositorio más allá del límite, nunca
`docker system prune -af`, luego los prune genéricos siempre seguros).

### Ingress compartido: Traefik (`deploy/vm-infra/traefik/`)

Dominio `64bitstudio.com` (Cloudflare gestiona su DNS). Subdominios por
ambiente: `auth.64bitstudio.com` (prod), `auth-qa.64bitstudio.com` (qa),
`auth-dev.64bitstudio.com` (dev). **Cuidado**: `mail.64bitstudio.com` YA es
el hostname del MTA de `mail-core-mc` (ticket 009) — no reutilizar; ese
proyecto debe usar algo como `api-mail.64bitstudio.com` para su propia app.

Compartido por TODA la VM, no específico de auth-core-mc — igual criterio
que el runner self-hosted: se versiona en este repo por ser el primer
proyecto en configurarlo, pero `mail-core-mc` debe reusar esta MISMA
instancia (conectar su propio stack a la red externa `edge` con sus
propias labels), no levantar un segundo Traefik.

**Topología real (decisión de Marco, ajustada tras el primer intento):** el
primer diseño tenía a Traefik publicando 80/443 directo y terminando TLS
él mismo con Let's Encrypt (HTTP-01). Al levantarlo en vivo, el puerto 80
ya estaba ocupado por **nginx** — paquete de fábrica de la imagen de
Ubuntu de la VM, systemd habilitado, sirviendo solo la página default sin
modificar (confirmado antes de tocar nada: contenido stock de
`/etc/nginx/sites-available/default`, nada más en `sites-enabled`/
`conf.d`, sin mención en el ticket 009 de `mail-core-mc`). La opción obvia
(deshabilitarlo) se le planteó a Marco explícitamente antes de aplicarla
— **decisión: NO deshabilitarlo**. En su lugar:

- **nginx se queda como puerta de entrada pública** en 80/443 — vhost
  nuevo (`deploy/vm-infra/nginx/auth-core-mc.conf`, instalado por el job
  `sync-vm-infra` vía `sudo cp` + `sites-enabled` + `nginx -t` +
  `systemctl reload`, nunca `disable`) que hace reverse proxy de los 3
  subdominios de auth-core-mc hacia Traefik, preservando el header `Host`.
- **Traefik queda puertas adentro**, publicado SOLO en
  `127.0.0.1:8000` (loopback) — nunca alcanzable directo desde internet,
  solo desde el propio nginx de la misma VM. Sigue resolviendo el ruteo
  real por `Host()` hacia el contenedor correcto vía sus labels/red
  `edge`, igual que antes.
- **TLS lo termina nginx, no Traefik** — sin ACME/Let's Encrypt en la
  config de Traefik (se quitó `certificatesResolvers` y el entrypoint
  `websecure`; queda un único entrypoint `web` en `:8000`, HTTP plano
  puertas adentro). El plan para el certificado real: una vez Marco cree
  los 3 registros DNS, correr `sudo certbot --nginx -d auth.64bitstudio.com
  -d auth-qa.64bitstudio.com -d auth-dev.64bitstudio.com` en la VM — el
  plugin de nginx de certbot agrega el bloque `443 ssl` y el redirect
  80→443 automáticamente, sin tener que volver a tocar el vhost a mano.
  **Pendiente de ejecutar** — depende del DNS, que depende de Marco (ver
  "Bloqueos de esta fase").
- Se descartó la alternativa "nginx solo reenvía `/.well-known/acme-
  challenge/` a Traefik y Traefik sigue emitiendo sus propios
  certificados" — un solo punto de terminación TLS (nginx) es más simple
  de operar y depurar que dos ACME clients coordinándose.

Cada app (`docker-compose.{dev,qa,prod}.yml`) se conecta también a la red
externa `edge` (además de su red privada con Postgres/Redis) y declara
labels de Traefik — sigue publicando su puerto de host además, sin
Traefik de por medio, porque el healthcheck del propio pipeline le pega a
`localhost:<puerto>` directo desde el runner que vive en la misma VM.

Dashboard/API de Traefik deliberadamente NO habilitado — sin decisión
todavía de a qué hostname/auth quedaría expuesto.

**Bootstrap desde este repo, no manual por SSH**: todos los cambios de
infra de la VM (Traefik, SonarQube, ambientes) se aplican vía un job de CI
(`sync-vm-infra`/los propios `deploy-*`) que corre en el runner self-hosted
que YA vive en la VM — nunca por SSH directo desde una sesión de agente.
Un `mkdir`/`docker compose up` corrido directo por SSH está bloqueado por
el clasificador de permisos del harness (correcto: es una escritura de
infra sin pasar por el pipeline versionado). Solo lectura (`docker ps`,
`cat`, `free`, etc.) está permitida por SSH para verificar estado.

### `application-deploy.properties` (perfil nuevo, `SPRING_PROFILES_ACTIVE=deploy`)

Hallazgo real al intentar dockerizar (sigue vigente, no cambió con el
rediseño): el backend **no tenía ninguna configuración explícita de
`spring.datasource.*`/`spring.data.redis.*`** — todo el tiempo dependió de
`spring-boot-docker-compose` (`developmentOnly` en `build.gradle`)
auto-detectando `backend/compose.yaml` en desarrollo local. Esa
dependencia se excluye automáticamente del jar empaquetado (`bootJar`) —
el jar que corre dentro del contenedor Docker no tenía ninguna otra forma
de encontrar su base de datos y habría fallado al arrancar en cualquier
despliegue real. Se resolvió con un perfil Spring nuevo
(`application-deploy.properties`, activado solo por
`docker-compose.{dev,qa,prod}.yml` vía `SPRING_PROFILES_ACTIVE=deploy`),
no tocando `application.properties` — cero riesgo de regresión en
`bootRun`/tests locales, que nunca activan ese perfil. `DB_PASSWORD` sin
default (falla ruidoso si falta, misma filosofía que `ResendEmailSender`).

### Imagen Docker: `backend/Dockerfile`, multi-stage, sin registry externo

`eclipse-temurin:25-jdk-noble` (build) → `eclipse-temurin:25-jre-noble`
(runtime, usuario no-root, `curl` instalado solo para el healthcheck de
compose). Build sin tests (`-x test -x sonar`, ya corridos en
`build-test-analyze` antes de este job) — evita Docker-in-Docker para
Testcontainers. Multi-arch de fábrica (amd64/arm64): el mismo Dockerfile
sirve igual en la VM (ARM64) sin flags de plataforma.

Sin registry externo (GHCR/Docker Hub descartados a propósito, decisión ya
tomada en el ticket): mismo host construye (`build-image`) y despliega
(`deploy-dev`/`deploy-qa`/`deploy-prod`) — cada promoción es un
`docker tag` local, no un push/pull.

### Convenciones de la VM (compartidas con `mail-core-mc` — reusar, no reinventar)

- **Secrets de despliegue fuera del checkout de git**:
  `/home/ubuntu/secrets/<repo>/.env.{dev,qa,prod}` en la VM — NUNCA dentro
  de la carpeta donde `actions/checkout` clona el repo. Un runner
  self-hosted limpia el workspace (`git clean`) antes de cada checkout; un
  archivo gitignored ahí adentro se borraría en cada run. Plantillas
  committeadas: `deploy/.env.{dev,qa,prod}.example`.
- **Nombres de proyecto de Compose explícitos** (`name:` en cada
  `docker-compose.*.yml`) — mismo motivo que `backend/compose.yaml`
  (ticket 007): sin esto, dos proyectos en la misma VM con servicios del
  mismo nombre genérico (`postgres`, `redis`, `app`) podrían chocar.
- **Puertos de host reservados por este proyecto en la VM**: DEV → 8081,
  QA → 8082, PROD → 8080. `mail-core-mc` (ticket gemelo 011) debe reservar
  puertos distintos — coordinar antes de registrar su stack.
- **Runner self-hosted único de la VM, label `vm-oci`**: desde el
  rediseño, es el ÚNICO runner self-hosted del proyecto — el de la Mac fue
  retirado. `mail-core-mc` reusa este mismo runner (registrado a nivel de
  organización), no registra uno nuevo.
- **SonarQube y Traefik son infra COMPARTIDA de la VM**, no de este
  proyecto — ver sus secciones arriba. `mail-core-mc` reusa ambos.
- **Vault Transit** (cifrado de secretos de tenant, ticket 017) sigue
  corriendo solo en `~/dev-infra` en la Mac — la VM no tiene Vault propio
  todavía. `VAULT_ADDR`/`VAULT_ROOT_TOKEN` quedan vacíos por defecto en
  `deploy/.env.{dev,qa,prod}.example`: cualquier tenant que intente
  configurar un secreto de proveedor social fallará ruidosamente (por
  diseño) hasta que se decida cómo la VM alcanza un Vault real. **No
  resuelto por este ticket** — se documenta aquí como hallazgo, no se
  asume una solución.

### Bloqueos de esta fase (rediseño) — TODOS resueltos salvo uno fuera de alcance

**Resueltos:**
- `allow_auto_merge` del repo — habilitado por Marco directamente.
- Desregistro del runner de la Mac de auth-core-mc (`marco-mac-auth-core-mc`,
  id 2) — borrado del registro de GitHub vía API por Marco (estaba
  offline localmente desde antes, ya no aparece en la lista de runners).
- 3 registros DNS en Cloudflare (`auth`/`auth-qa`/`auth-dev`.64bitstudio.com
  → 159.54.153.37) — creados por Marco.
- Certificado real de Let's Encrypt — emitido por `certbot --nginx` una
  vez el DNS resolvió (ver certificado SAN único para los 3 subdominios,
  válido, en la sección de Traefik/nginx arriba).

**Sigue pendiente, fuera de alcance de este ticket:**
- Runner de la Mac de `mail-core-mc` (`marco-mac-mail-core-mc`) — existe
  un SEGUNDO runner repo-level para ese proyecto, todavía activo. La
  misma decisión ("la Mac solo codifica") aplicaría ahí, pero es del
  ticket gemelo (011) de `mail-core-mc` — se deja anotado, sin tocar.

### Verificación de punta a punta — hallazgos reales encontrados y corregidos

Tras el primer merge real a `dev` (feature/049-rediseno-dev-qa-prod →
`dev`, disparando por primera vez el pipeline completo del rediseño),
aparecieron 4 bugs reales más, cada uno solo visible al correr el
pipeline de verdad contra la VM — ninguno se hubiera visto en revisión
de código. Se documentan todos, en el orden en que aparecieron:

1. **`deploy/.env.dev`/`deploy/.env.qa` nunca existieron en la VM** — el
   primer `deploy-dev` falló con `couldn't find env file`. El `.env.test`
   del modelo anterior no se había renombrado/migrado solo (obvio en
   retrospectiva: nadie lo hace por uno). Fix: paso idempotente en
   `deploy-dev`/`deploy-qa` que genera el archivo real desde su
   `.example` (con `DB_PASSWORD` vía `openssl rand`, nunca impreso en el
   log) si todavía no existe.
2. **El stack `auth-core-mc-test` del modelo anterior seguía corriendo y
   ocupaba el puerto 8081** — el mismo que ahora usa DEV. Se retira
   (`docker compose -p auth-core-mc-test down`) antes del `up` del nuevo
   stack — TEST/DEV siempre fue disposable por diseño, sin garantía de
   rollback, así que no se migraron datos. El viejo `auth-core-mc-prod`
   SÍ se dejó corriendo tal cual (mismo nombre de proyecto/puerto que el
   PROD nuevo — se actualiza solo, sin conflicto, cuando corra el primer
   `deploy-prod` real).
3. **`cleanup.sh` borró la imagen de release QUE ESTABA EN USO** —
   `deploy-dev` desplegó bien (3 contenedores healthy, healthcheck real
   en verde), pero el paso de limpieza falló: `image is being used by
   running container`. Causa confirmada en la VM: dos imágenes de SHAs
   distintos con el **mismo `CreatedAt` exacto** (el código de
   `./backend` no había cambiado entre esos commits, así que `docker
   build` reutilizó el cache de capas completo). El script anterior
   ordenaba solo por fecha para decidir cuál es "la actual" — con ese
   empate real, el orden no estaba garantizado. Fix: la imagen actual se
   resuelve del tag `:current` (no de la fecha), y además nunca se borra
   una imagen que un contenedor corriendo esté usando de verdad
   (`docker ps --filter ancestor=<id>`), sin importar qué diga el cálculo
   de retención — salvaguarda independiente. Aplica a los 3 ambientes.
4. **Traefik nunca pudo leer el provider Docker, desde el día uno** — con
   el deploy y el certificado ya funcionando, `auth-dev.64bitstudio.com`
   daba 404: cero routers registrados. Traefik reintentaba en loop
   `client version 1.24 is too old` — el cliente Docker embebido en
   `traefik:v3.1` negociaba API 1.24 contra el daemon real (1.55), que ya
   rechaza clientes tan viejos (bug conocido,
   [traefik/traefik#12253](https://github.com/traefik/traefik/issues/12253),
   causado por Docker Engine 29+ subiendo su mínimo soportado). Primer
   intento de fix (fijar `DOCKER_API_VERSION=1.47` a mano) **no
   funcionó** — confirmado en vivo que el contenedor recreado seguía
   fallando igual pese a tener la variable puesta. El fix real
   ([traefik/traefik#12256](https://github.com/traefik/traefik/pull/12256))
   es la negociación automática de versión, agregada en v3.6+ — se subió
   la imagen a `traefik:v3.7.12`.
5. **El puerto 443 nunca estuvo abierto en el Security List de OCI** —
   distinto del firewall local (iptables) que ya se había abierto antes.
   El VNIC de la VM no tiene NSGs; las reglas viven en el Security List
   del subnet, que solo tenía ingress para 22 (SSH) y 80 (HTTP) — nadie
   había necesitado 443 hasta ahora. Verificado con `oci network
   security-list get` y corregido con `oci network security-list
   update` (agregando la regla ingress 443/tcp desde 0.0.0.0/0, mismo
   patrón que usa `oci_a1_grab.sh`). **Importante para no repetir el
   error de diagnóstico**: un `curl` hecho DESDE la propia VM contra su
   propio hostname/IP público puede fallar por "hairpin NAT" del cloud
   incluso con el puerto ya bien abierto — la prueba real tiene que
   hacerse desde AFUERA de la VM (o con `curl --resolve host:puerto:127.0.0.1`
   para probar el servicio local sin depender del enrutamiento externo).

Verificado desde afuera de la VM, de punta a punta, tras estos 5 fixes:
`curl https://auth-dev.64bitstudio.com/actuator/health` → `200`,
`{"status":"UP",...}` real. Mismo resultado para
`auth-qa.64bitstudio.com` tras el merge de validación `dev → qa`.

### Política de merges por rama (vigente)

- `feature/NNN → dev`: automático (`allow_auto_merge` + el pipeline en
  verde), sin acción humana por commit.
- `dev → qa`: lo mergea el **orquestador** (la sesión principal de
  Claude Code), no este agente ni Marco directamente — se abre el PR y
  se avisa para que lo mergeen desde ahí.
- `qa → prod`: **exclusivo de Marco**, siempre, sin excepción — ni este
  agente ni el orquestador lo tocan nunca, ni siquiera "para probar el
  mecanismo". Se abre el PR y se espera a que Marco decida y ejecute el
  merge él mismo.

### Recursos de la VM — atención continua, no una decisión de una sola vez

2 OCPU/12GB compartidos entre Postfix (mail-core-mc), SonarQube+su
Postgres, los stacks dev/qa/prod (cada uno con su propio Postgres+Redis),
Traefik y el runner. Medido antes de levantar SonarQube en la VM: 1.7GB en
uso, ~9GB disponibles (`free -h` real, no estimado). "Bajo demanda" en
dev/qa ya es la mitigación decidida por Marco — si el uso real después de
levantar todo esto se acerca al límite, se reporta con números concretos
(no se decide nada por asunción, como apagar algo o cambiar límites de
memoria, sin que Marco lo confirme primero).

### SEGUNDO PIVOTE: de GitHub Actions a Jenkins (2026-08-30, decisión deliberada de Marco)

Con DEV y QA ya validados de punta a punta sobre GitHub Actions, Marco
pidió migrar la ORQUESTACIÓN del pipeline a Jenkins — no por indecisión,
sino por una necesidad real de UX que GitHub Actions no cubre: ver desde
un frontend qué cambios llegaron a QA y cuándo, con un botón real para
promoverlos a PROD, el pipeline visualizado, y enlaces a SonarQube/
Traefik. Jenkins cubre esto nativo (steps `input` con botón real,
vista de pipeline, historial de builds) — no se construye ningún
frontend nuevo.

**Se conserva TODO lo ya construido** — Dockerfiles, `docker-compose.
{dev,qa,prod}.yml`, `cleanup.sh`, Traefik, SonarQube en la VM, la
convención de secrets fuera del checkout, las 3 ramas dev/qa/prod como
fuente de verdad en git. Lo que cambia es solo QUIÉN orquesta: Jenkins
en vez de `.github/workflows/ci.yml` — que **se mantiene corriendo en
paralelo** hasta confirmar que Jenkins funciona de punta a punta (no se
retira todavía, para no dejar el pipeline sin ninguna forma de
desplegar durante la migración).

**Medición de recursos ANTES de instalar nada** (pedida explícitamente
por Marco antes de proceder): con SonarQube+Traefik+dev/qa/prod ya
corriendo, `free -h` real mostró 11Gi totales, 5.0Gi en uso, **6.6Gi
disponibles**. Desglose por contenedor (`docker stats --no-stream`):
SonarQube solo, 2.75GB (el consumidor más grande con diferencia); cada
stack de la app (dev/qa/prod), ~400-435MB; postgres de cada ambiente,
~42MB; Traefik, 44MB. CPU: **solo 2 vCPU** — señalado como el punto real
a vigilar (no bloqueante): SonarQube, el controller de Jenkins y los
builds de Gradle son todos JVMs que compiten por los mismos 2 cores
durante un build real, aunque en reposo el uso de CPU es prácticamente
nulo. Marco confirmó proceder con estos números.

**Diseño e implementación**:
- **Jenkins como contenedor Docker** (`deploy/vm-infra/jenkins/`),
  infra COMPARTIDA de la VM igual que Traefik/SonarQube (mail-core-mc
  debe reusar esta misma instancia, no levantar una segunda). Imagen
  propia (`Dockerfile` sobre `jenkins/jenkins:lts-jdk21`) porque la
  oficial no trae el CLI de Docker.
- **docker.sock montado, NO Docker-in-Docker** — Jenkins necesita
  construir/desplegar contra el MISMO daemon Docker de la VM que ya
  usan los compose files y la red `edge`; un daemon anidado rompería
  esa continuidad (habría que reconstruir imágenes/redes otra vez
  adentro). Riesgo aceptado y documentado: monta docker.sock le da a
  cualquier job de Jenkins acceso equivalente a root en el host — pero
  es EXACTAMENTE el mismo nivel de acceso que ya tiene el runner
  self-hosted de GitHub Actions (usuario `ubuntu`, grupo `docker`, sin
  sandboxing extra), no una categoría de riesgo nueva. Mitigación real:
  solo Marco tiene login de administrador en Jenkins.
- **Hallazgo real de docker.sock** (gotcha clásico, encontrado antes de
  desplegar, no en producción): cuando el CLI de Docker corre DENTRO de
  un contenedor pero habla con el daemon del HOST vía el socket, un
  flag como `--env-file /home/ubuntu/secrets/...` lo resuelve el propio
  proceso del CLI (adentro del contenedor), no el host. Sin ese mismo
  path también disponible ADENTRO del contenedor de Jenkins, el
  `--env-file` habría fallado con "no such file" pese a que el archivo
  sí existe en la VM. Se monta `/home/ubuntu/secrets` 1:1 (solo
  lectura) en el contenedor de Jenkins para que ambos "vean" la misma
  ruta.
- **Configuration as Code (plugin `configuration-as-code`)** —
  seguridad, plugins y credenciales se declaran en
  `deploy/vm-infra/jenkins/casc/jenkins.yaml`, versionado, no "hecho a
  mano" sin rastro. El único usuario administrador (`marco`, password
  generado la primera vez, que Marco debe cambiar de inmediato al
  entrar) y las credenciales (PAT de GitHub, token de Sonar, Telegram)
  se inyectan vía variables de entorno del contenedor, leídas de
  `/home/ubuntu/secrets/jenkins/.env` — nunca hardcodeadas en ningún
  archivo del repo. El job Multibranch Pipeline en sí (el que de verdad
  lee el `Jenkinsfile` de cada rama) se crea a mano, una sola vez, desde
  la UI — se evaluó automatizarlo también vía Job DSL en el mismo
  `jenkins.yaml`, pero el riesgo de tumbar el arranque completo de
  Jenkins por un error de sintaxis en el DSL (sin poder iterar en vivo
  contra la UI real) no valía la pena por un setup de 2-3 minutos que
  Marco hace una sola vez.
- **Red compartida nueva `vm-infra`** (distinta de `edge`, que es solo
  para tráfico público vía Traefik) — Jenkins necesita alcanzar a
  SonarQube por nombre de contenedor (`sonarqube:9000`), no por
  `localhost` (cada contenedor tiene su propio loopback). SonarQube se
  conectó también a esta red nueva.
- **Ingress**: mismo patrón que auth-core-mc — nginx (puerta pública)
  reenvía `jenkins.64bitstudio.com` a Traefik, que rutea al contenedor
  de Jenkins por label. **Pendiente de Marco**: el registro DNS de
  `jenkins.64bitstudio.com` (mismo patrón que los subdominios de
  auth-core-mc) — hasta que exista, el certificado real de Let's
  Encrypt para Jenkins queda con `continue-on-error` en el pipeline (no
  tumba el resto del job mientras tanto, se emite solo en cuanto el DNS
  resuelva).
- **`Jenkinsfile`** (raíz del repo) reemplaza a `ci.yml` como
  orquestador: mismas etapas (Sonar con Quality Gate real vía el plugin
  oficial `sonar` + un webhook Sonar→Jenkins configurado por API →
  build de imagen → deploy). `feature/NNN`/`dev` construyen y despliegan
  a DEV automático; `qa` promueve (sin rebuild) la imagen validada en
  DEV a QA automático, y termina en un stage `input` — el botón real que
  pidió Marco — con `submitter: 'marco'` (solo él puede aprobarlo). Al
  aprobar: promueve (sin rebuild) la misma imagen a PROD, corre
  `cleanup.sh prod`, y actualiza la rama `prod` en git (push directo,
  vía el PAT) para que el historial de ramas siga reflejando qué hay
  realmente en producción — ya no es un merge de PR lo que dispara ese
  despliegue, así que el registro en git pasa a ser una consecuencia del
  pipeline, no su disparador.
- **Duplicación temporal aceptada, no oculta**: mientras ambos pipelines
  corren en paralelo, el mismo commit se analiza dos veces en Sonar (una
  vez por `ci.yml`, otra por el Jenkinsfile). Se resuelve al retirar
  `ci.yml`, una vez Jenkins esté confirmado funcionando de punta a
  punta — todavía no.

**Pendiente de una acción directa de Marco** para terminar de cerrar
este pivote:
1. ~~Completar el setup wizard de Jenkins~~ — hecho, confirmado con un
   login real (POST a `j_spring_security_check`).
2. ~~Generar un PAT de GitHub~~ — hecho (fine-grained, `All repositories`
   de `64bitstudio`, `Contents read/write` + `Metadata read` +
   `Webhooks read/write`), puesto en
   `/home/ubuntu/secrets/jenkins/.env`.
3. Crear el registro DNS de `jenkins.64bitstudio.com` → `159.54.153.37`
   — ya resuelve (confirmado), el certificado real de Let's Encrypt
   también ya se emitió.
4. Crear el job Multibranch Pipeline desde la UI de Jenkins (apuntando
   al repo, con la credencial `github-pat`) y configurar el webhook de
   GitHub hacia Jenkins — **todavía pendiente**.
5. Confirmar de punta a punta que el Jenkinsfile despliega igual que
   `ci.yml` antes de retirar este último — pendiente del punto 4.

**Hallazgo real (`docker restart` vs. recrear el contenedor)**: tras
poner el PAT real en el `.env` y reiniciar el contenedor de Jenkins, la
variable `GITHUB_PAT` seguía vacía DENTRO del contenedor
(`docker exec jenkins printenv GITHUB_PAT` → vacío), pese a que el
archivo en la VM sí tenía el valor correcto. Causa: un `docker restart`
(o equivalente) solo para/arranca el MISMO contenedor con el entorno ya
congelado desde su creación — nunca vuelve a leer el `.env` ni el
`docker-compose.yml`. Para que una variable de entorno nueva tome
efecto hace falta recrear el contenedor (`docker compose up -d`, que sí
compara la config resuelta contra la que corre y recrea si difiere) —
exactamente lo que ya hace el paso "Jenkins (orquestador del pipeline)"
de `sync-vm-infra` en cada push a `dev`. Se corrige disparando ese job
de nuevo (este mismo commit de docs lo dispara), sin necesitar tocar la
VM a mano.

## Estado de este documento
_Última actualización: ticket `049`, SEGUNDO pivote — de GitHub Actions a
Jenkins como orquestador (Marco, 2026-08-30), con el diseño y la infra de
Jenkins ya implementados (contenedor, JCasC, Jenkinsfile, redes) pero
`ci.yml` todavía activo en paralelo y varios pasos manuales pendientes de
Marco (ver la sección del pivote arriba). Antes de esto: DEV y QA
validados de punta a punta de verdad (deploy real, healthcheck real,
Traefik+TLS real, retención de imágenes real) tras el PRIMER rediseño
dev/qa/prod — ver la sección del ticket 049 arriba para la historia
completa (diseño original reemplazado, y los 5 hallazgos reales de la
verificación de punta a punta). PROD queda pendiente de que Marco decida
promover algo real vía `qa → prod` — esa promoción es exclusiva suya, no
se dispara ni se simula desde ningún agente. Antes de este ticket, al
cerrar la tarea `048` (grant `client_credentials` para clientes
machine-to-machine). La fase 2 del rediseño de UI (tickets 024-030,
`docs/definiciones/rediseno-ui-fase-2.md`) sigue cerrada como epic; 031-034
son ajustes puntuales posteriores; 035 a 047 son la épica de login social
real (`docs/definiciones/login-social-real.md`), cerrada sin tickets de
seguimiento pendientes. El 048 es trabajo nuevo fuera de esa épica, pedido
por un servicio externo (`mail-core-mc`) — deja pendiente, fuera de este
proyecto, arreglar el symlink roto de Docker en esta máquina (nota arriba)
y, del lado de `mail-core-mc`, terminar su propio ticket 005 (resource
server) contra este grant ya funcional._
