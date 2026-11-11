# Lendix

Plataforma privada para gestionar préstamos personales en Perú (PEN, zona horaria `America/Lima`).

## Estructura

- `lendix-be`: backend Java/Spring WebFlux + R2DBC + PostgreSQL.
- `lendix-fe`: frontend Angular standalone + Signals.

## Configuración local

1. Copiar `lendix-be/.env.example` a `lendix-be/.env` y completar las variables sin subirlas a Git.
2. Ejecutar las migraciones y el backend con Maven.
3. En otra terminal, ejecutar `npm install` y `npm start` dentro de `lendix-fe`.

La conexión de Neon nunca se guarda en el repositorio. Si la URL compartida contiene una contraseña real, debe rotarse en Neon antes de usarla en un entorno compartido.

