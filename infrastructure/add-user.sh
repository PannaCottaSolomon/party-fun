#!/usr/bin/env bash
# Creates a Cognito user with a permanent password and puts them in one of the three groups.
#
#   ./add-user.sh admin admin@example.com 'Passw0rdExample' admin
#   ./add-user.sh alice alice@example.com 'Passw0rdExample' participant
#
# The user signs in with the username or the email. Use it for the admin account (nobody can make themselves an
# admin) and for test users. If the user already exists, only the password and group are set. Needs the cognito
# stack deployed (./deploy.sh).
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "ERROR: $*" >&2; exit 1; }

[ $# -eq 4 ] || fail "usage: $0 <username> <email> <password> <participant|organiser|admin>"
USERNAME="$1" EMAIL="$2" PASSWORD="$3" GROUP="$4"
case "$USERNAME" in *@*) fail "the username can't contain '@' (the email is a separate sign-in alias)" ;; esac
case "$EMAIL" in *@*) ;; *) fail "'$EMAIL' is not an email address" ;; esac
case "$GROUP" in participant|organiser|admin) ;; *) fail "group must be participant, organiser or admin" ;; esac

[ -f config.env ] || fail "config.env not found: copy config.env.example to config.env and edit it"
set -a
# shellcheck disable=SC1090
. <(tr -d '\r' < config.env)   # tolerate Windows line endings
set +a
AWS_REGION="${AWS_REGION:-ap-southeast-1}"
STACK_PREFIX="${STACK_PREFIX:-partyfun}"
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"
if [ -n "${AWS_PROFILE:-}" ]; then export AWS_PROFILE; else unset AWS_PROFILE; fi

POOL=$(aws cloudformation describe-stacks --stack-name "$STACK_PREFIX-cognito" \
  --query "Stacks[0].Outputs[?OutputKey=='UserPoolId'].OutputValue" --output text) \
  || fail "stack $STACK_PREFIX-cognito not found: run ./deploy.sh first"

if aws cognito-idp admin-get-user --user-pool-id "$POOL" --username "$USERNAME" >/dev/null 2>&1; then
  echo "User $USERNAME already exists; setting the password and group."
else
  aws cognito-idp admin-create-user --user-pool-id "$POOL" --username "$USERNAME" \
    --user-attributes Name=email,Value="$EMAIL" Name=email_verified,Value=true \
    --message-action SUPPRESS >/dev/null
  echo "Created $USERNAME ($EMAIL)."
fi
aws cognito-idp admin-set-user-password --user-pool-id "$POOL" --username "$USERNAME" \
  --password "$PASSWORD" --permanent
aws cognito-idp admin-add-user-to-group --user-pool-id "$POOL" --username "$USERNAME" --group-name "$GROUP"
echo "$USERNAME is in the '$GROUP' group. Their tokens carry \"cognito:groups\": [\"$GROUP\"]."
