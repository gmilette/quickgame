#!/bin/sh
set -e
cd "$(dirname "$0")"
./gradlew :server:installDist --console=plain -q
gcloud run deploy quickgames-server \
  --project quickgame-336e4 \
  --region us-central1 \
  --source . \
  --min-instances=0 --max-instances=2 \
  --cpu-throttling \
  --memory 512Mi \
  --no-allow-unauthenticated \
  --set-env-vars GOOGLE_CLOUD_PROJECT=quickgame-336e4 \
  --quiet
