#!/bin/sh
set -e
command -v gcloud > /dev/null || PATH="$PATH:$HOME/dev/greenopal/google-cloud-sdk/bin"
command -v gcloud > /dev/null || { echo "gcloud not found. Install the Google Cloud CLI or add it to PATH."; exit 1; }
P=quickgame-336e4
R=us-central1
SA=quickgames-invoker@$P.iam.gserviceaccount.com

trigger() {
  if gcloud eventarc triggers describe "$1" --project $P --location $R > /dev/null 2>&1; then
    echo "trigger $1 already exists"
    return
  fi
  gcloud eventarc triggers create "$1" --project $P --location $R \
    --destination-run-service quickgames-server --destination-run-region $R --destination-run-path "$2" \
    --event-filters type="$3" \
    --event-filters database='(default)' \
    --event-filters-path-pattern document="$4" \
    --event-data-content-type application/protobuf \
    --service-account $SA
}

gcloud services enable fcm.googleapis.com --project $P

trigger quickgames-open-game /events/open-game google.cloud.firestore.document.v1.updated 'openGames/{openId}'
trigger quickgames-submission /events/submission google.cloud.firestore.document.v1.created 'games/{gameId}/submissions/{subId}'
trigger quickgames-resignation /events/resignation google.cloud.firestore.document.v1.created 'games/{gameId}/resignations/{uid}'

if gcloud eventarc triggers describe quickgames-queue-entry --project $P --location $R > /dev/null 2>&1; then
  gcloud eventarc triggers delete quickgames-queue-entry --project $P --location $R --quiet
fi
echo "Triggers are set up. New triggers can take two minutes to become active."
