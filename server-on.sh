#!/bin/sh
set -e
command -v gcloud > /dev/null || PATH="$PATH:$HOME/dev/greenopal/google-cloud-sdk/bin"
command -v gcloud > /dev/null || { echo "gcloud not found. Install the Google Cloud CLI or add it to PATH."; exit 1; }
P=quickgame-336e4
R=us-central1
SA=quickgames-invoker@$P.iam.gserviceaccount.com
gcloud run services add-iam-policy-binding quickgames-server --project $P --region $R \
  --member serviceAccount:$SA --role roles/run.invoker --quiet > /dev/null
gcloud scheduler jobs resume quickgames-sweep --project $P --location $R --quiet > /dev/null
echo "Server is ON: Firestore events and the weekly sweep reach it again."
