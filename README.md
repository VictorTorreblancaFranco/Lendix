# Lendix

Plataforma privada para gestionar préstamos personales en Perú (PEN, zona horaria `America/Lima`).

## Estructura

- `lendix-be`: backend Java/Spring WebFlux + R2DBC + PostgreSQL.
- `lendix-fe`: frontend Angular standalone + Signals.

## Configuración local

1. Copiar `lendix-be/.env.example` a `lendix-be/.env` y completar las variables sin subirlas a Git.
2. Para desarrollo local, levantar PostgreSQL con `docker compose up -d postgres`; para Neon, reemplazar `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FLYWAY_URL`, `FLYWAY_USER` y `FLYWAY_PASSWORD` por las variables de tu proyecto.
3. Ejecutar las migraciones y el backend con Maven.
4. En otra terminal, ejecutar `npm install` y `npm start` dentro de `lendix-fe`.

El backend exige `LENDIX_ADMIN_USERNAME` y `LENDIX_ADMIN_PASSWORD`; las rutas de negocio se consumen con autenticación HTTP Basic durante esta primera versión. El endpoint `/api/v1/health` queda público para comprobaciones.

La conexión de Neon nunca se guarda en el repositorio. Si la URL compartida contiene una contraseña real, debe rotarse en Neon antes de usarla en un entorno compartido.

