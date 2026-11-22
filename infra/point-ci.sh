#!/usr/bin/env bash
# Points the GitHub Actions workflows at the project in infra/terraform, after `terraform apply`
# there: the keyless sign-in (Workload Identity), the service accounts, the project ID and the
# API address. Run from the repository root, then commit .github/workflows.
set -euo pipefail
out() { terraform -chdir=infra/terraform output -raw "$1"; }

provider=$(out github_workload_identity_provider)      # projects/NUMBER/locations/global/...
deployer=$(out deployer_service_account)
android_ci=$(out android_ci_service_account)
api_url=$(out api_url)
project=${deployer#*@}; project=${project%%.iam.gserviceaccount.com}

for f in .github/workflows/*.yml; do
  sed -i -E \
    -e "s#workload_identity_provider: projects/[0-9]+/locations/global/workloadIdentityPools/github/providers/github-actions#workload_identity_provider: ${provider}#" \
    -e "s#service_account: found-with-deployer@[a-z0-9-]+\.iam\.gserviceaccount\.com#service_account: ${deployer}#" \
    -e "s#service_account: found-with-android-ci@[a-z0-9-]+\.iam\.gserviceaccount\.com#service_account: ${android_ci}#" \
    -e "s#--project=[a-z][a-z0-9-]+ --secret=#--project=${project} --secret=#g" \
    -e "s#^(  PROJECT_ID|  PROJECT): [a-z][a-z0-9-]+\$#\1: ${project}#" \
    -e "s#https://found-with-api-[a-z0-9]+-el\.a\.run\.app#${api_url}#g" \
    "$f"
done
echo "Workflows now use project ${project} and ${api_url}"
grep -rn "workload_identity_provider:\|service_account:\|--project=\|PROJECT_ID:\|PROJECT:\|a.run.app" .github/workflows/ | sed 's/^/  /'
