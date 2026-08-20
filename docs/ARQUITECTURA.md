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

## Estado de este documento
_Última actualización: al cerrar la tarea `000` (directiva de documentación). Se actualizará con cada ticket movido a `/done`._
