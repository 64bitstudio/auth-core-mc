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

## Lecciones del ticket 001 (por qué los tests están configurados así)

- **`@DataJpaTest` no usa Flyway por defecto**: genera el esquema directamente desde las anotaciones `@Entity`, lo cual habría dejado los tests corriendo contra un esquema paralelo que nunca valida que `V1__init.sql` sea correcto. Se forzó `spring.jpa.hibernate.ddl-auto=validate` en `backend/src/test/resources/application.properties` para que Hibernate solo *valide* contra lo que Flyway ya creó, nunca lo genere.
- **La traducción de excepciones de Spring solo aplica a llamadas a través del proxy `@Repository`**: si el test llama `entityManager.flush()` directamente (para forzar el INSERT diferido de Hibernate), la excepción que sale es la nativa de Hibernate (`org.hibernate.exception.ConstraintViolationException`), no la traducida de Spring (`org.springframework.dao.DataIntegrityViolationException`). Ambas ocurren en este proyecto según de dónde se dispare el flush — ver los tests de `UserRepositoryTest` para el patrón exacto.
- **OrbStack se suspende solo por inactividad** y detiene todos los contenedores (Testcontainers de los tests, SonarQube). Cualquier `./gradlew test` puede fallar con `DockerClientProviderStrategy`/`IllegalStateException` simplemente porque OrbStack estaba dormido — solución: `open -a OrbStack` y esperar unos segundos antes de reintentar.

## Estado de este documento
_Última actualización: al cerrar la tarea `004` (recuperación de contraseña). Se actualizará con cada ticket movido a `/done`._
