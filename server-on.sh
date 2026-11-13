#!/bin/sh
set -e
P=quickgame-336e4
R=us-central1
SA=quickgames-invoker@$P.iam.gserviceaccount.com
gcloud run services add-iam-policy-binding quickgames-server --project $P --region $R \
  --member serviceAccount:$SA --role roles/run.invoker --quiet > /dev/null
gcloud scheduler jobs resume quickgames-sweep --project $P --location $R --quiet > /dev/null
echo "Server is ON: Firestore events and the weekly sweep reach it again."
