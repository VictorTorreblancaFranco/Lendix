#!/usr/bin/env bash
set -euo pipefail

# Uso:
#   export DB_URL='r2dbc:postgresql://...'
#   export DB_USERNAME='...'
#   export DB_PASSWORD='...'
#   export FLYWAY_URL='jdbc:postgresql://...'
#   export FLYWAY_USER='...'
#   export FLYWAY_PASSWORD='...'
#   export LENDIX_ADMIN_PASSWORD='una-clave-segura'
#   export LENDIX_FRONTEND_ORIGIN='https://tu-app.vercel.app'
#   ./deploy-cloud-run.sh lendix-514503

PROJECT_ID="${1:?Indica el PROJECT_ID de Google Cloud}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-lendix-be}"

: "${DB_URL:?Falta DB_URL}"
: "${DB_USERNAME:?Falta DB_USERNAME}"
: "${DB_PASSWORD:?Falta DB_PASSWORD}"
: "${FLYWAY_URL:?Falta FLYWAY_URL}"
: "${FLYWAY_USER:?Falta FLYWAY_USER}"
: "${FLYWAY_PASSWORD:?Falta FLYWAY_PASSWORD}"
: "${LENDIX_ADMIN_PASSWORD:?Falta LENDIX_ADMIN_PASSWORD}"
: "${LENDIX_FRONTEND_ORIGIN:?Falta LENDIX_FRONTEND_ORIGIN}"

gcloud config set project "$PROJECT_ID"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com

gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --platform managed \
  --allow-unauthenticated \
  --min 0 \
  --max 1 \
  --memory 512Mi \
  --cpu 1 \
  --concurrency 40 \
  --timeout 120 \
  --set-env-vars="LENDIX_AUTH_REQUIRED=true,LENDIX_ADMIN_USERNAME=admin,LENDIX_ADMIN_PASSWORD=${LENDIX_ADMIN_PASSWORD},LENDIX_FRONTEND_ORIGIN=${LENDIX_FRONTEND_ORIGIN},DB_URL=${DB_URL},DB_USERNAME=${DB_USERNAME},DB_PASSWORD=${DB_PASSWORD},FLYWAY_URL=${FLYWAY_URL},FLYWAY_USER=${FLYWAY_USER},FLYWAY_PASSWORD=${FLYWAY_PASSWORD}"

gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)'
