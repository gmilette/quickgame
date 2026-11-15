#!/bin/sh
set -e
command -v gcloud > /dev/null || PATH="$PATH:$HOME/dev/greenopal/google-cloud-sdk/bin"
command -v gcloud > /dev/null || { echo "gcloud not found. Install the Google Cloud CLI or add it to PATH."; exit 1; }
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
