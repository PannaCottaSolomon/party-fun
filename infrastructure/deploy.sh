#!/usr/bin/env bash
# Deploys the party.fun shared AWS services with CloudFormation, then builds and uploads the frontend.
#
#   ./deploy.sh                  everything; asks before deploying to the account your AWS credentials point at
#   ./deploy.sh --yes            everything, no question (e.g. in CI)
#   ./deploy.sh cognito api      only those parts
#
# Parts, always run in this order: secrets, cognito, frontend, cluster, rabbitmq, observability, api (stacks
# <prefix>-<part>) and site (build the
# React app, upload it, refresh CloudFront). Parts that aren't selected read what they need from the stacks already
# deployed. Re-running updates only what changed. Settings come from config.env (copy config.env.example). No secret
# value is printed.
#
#   ./deploy.sh chaos            the AWS FIS experiments (templates/chaos.yaml). Only when named: it needs the events
#                                and payment stacks, which deploy-events / deploy-payment create, not this script.
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "ERROR: $*" >&2; exit 1; }

ALL_PARTS="secrets cognito frontend cluster rabbitmq observability api site"
USAGE="usage: $0 [--yes] [secrets] [cognito] [frontend] [cluster] [rabbitmq] [observability] [api] [site] [chaos]"
YES=""
SELECTED=""
for arg in "$@"; do
  case "$arg" in
    --yes) YES=1 ;;
    secrets|cognito|frontend|cluster|rabbitmq|observability|api|site|chaos) SELECTED="$SELECTED $arg" ;;
    *) fail "$USAGE" ;;
  esac
done
[ -n "$SELECTED" ] || SELECTED="$ALL_PARTS"
# want <part>  - true if the part was selected
want() { case " $SELECTED " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
PARTS=""
for part in $ALL_PARTS chaos; do want "$part" && PARTS="$PARTS $part"; done
PARTS="${PARTS# }"

[ -f config.env ] || fail "config.env not found: copy config.env.example to config.env and edit it"
set -a
# shellcheck disable=SC1090
. <(tr -d '\r' < config.env)   # tolerate Windows line endings
set +a

AWS_REGION="${AWS_REGION:-ap-southeast-1}"
STACK_PREFIX="${STACK_PREFIX:-partyfun}"
FRONTEND_DIR="${FRONTEND_DIR:-../user-interfaces/frontend}"
VPC_ID="${VPC_ID:-}"
SUBNET_IDS="${SUBNET_IDS:-}"
RABBITMQ_ADMIN_CIDR="${RABBITMQ_ADMIN_CIDR:-127.0.0.1/32}"
JAEGER_ADMIN_CIDR="${JAEGER_ADMIN_CIDR:-127.0.0.1/32}"
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"
if [ -n "${AWS_PROFILE:-}" ]; then export AWS_PROFILE; else unset AWS_PROFILE; fi

command -v aws >/dev/null || fail "the AWS CLI v2 is not installed (https://aws.amazon.com/cli/)"
if want site; then
  command -v npm >/dev/null || fail "npm is not installed (needed to build the frontend)"
  [ -f "$FRONTEND_DIR/package.json" ] || fail "no frontend at FRONTEND_DIR=$FRONTEND_DIR"
fi

ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) \
  || fail "AWS credentials are missing or expired: run 'aws configure' (or 'aws sso login', or paste fresh lab credentials)"

echo "Deploying '$STACK_PREFIX' ($PARTS) to AWS account $ACCOUNT in $AWS_REGION${AWS_PROFILE:+ (profile $AWS_PROFILE)}."
if [ -z "$YES" ]; then
  read -r -p "Continue? [y/N] " answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "Cancelled."; exit 0; }
fi

# deploy_stack <name> <template> [Key=Value ...]  - creates or updates <prefix>-<name>
deploy_stack() {
  local name="$1" template="templates/$2"; shift 2
  echo
  echo "== $STACK_PREFIX-$name ($template)"
  aws cloudformation validate-template --template-body "file://$template" >/dev/null
  aws cloudformation deploy \
    --stack-name "$STACK_PREFIX-$name" \
    --template-file "$template" \
    --no-fail-on-empty-changeset \
    --capabilities CAPABILITY_IAM \
    --tags project="$STACK_PREFIX" \
    --parameter-overrides Prefix="$STACK_PREFIX" "$@"
}

# stack_exists <stack name>
stack_exists() {
  aws cloudformation describe-stacks --stack-name "$STACK_PREFIX-$1" >/dev/null 2>&1
}

# optional_output <stack name> <output key>  - empty if that stack or output doesn't exist
optional_output() {
  local value
  value=$(aws cloudformation describe-stacks --stack-name "$STACK_PREFIX-$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text 2>/dev/null) || true
  [ "$value" = "None" ] && value=""
  echo "$value"
}

# output <stack name> <output key>  - fails if that stack isn't deployed
output() {
  stack_exists "$1" || fail "stack $STACK_PREFIX-$1 not found: deploy '$1' first"
  aws cloudformation describe-stacks --stack-name "$STACK_PREFIX-$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

if want secrets; then deploy_stack secrets secrets.yaml; fi
if want cognito; then deploy_stack cognito cognito.yaml; fi
if want frontend; then deploy_stack frontend frontend.yaml; fi

if want cluster; then
  # No VPC set: use the account's default VPC and its default subnets (public, one per Availability Zone).
  if [ -z "$VPC_ID" ]; then
    VPC_ID=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
    [ -n "$VPC_ID" ] && [ "$VPC_ID" != "None" ] || fail "no default VPC in $AWS_REGION: set VPC_ID and SUBNET_IDS in config.env"
  fi
  if [ -z "$SUBNET_IDS" ]; then
    SUBNET_IDS=$(aws ec2 describe-subnets --filters Name=vpc-id,Values="$VPC_ID" Name=default-for-az,Values=true \
      --query 'Subnets[].SubnetId' --output text | tr '\t' '\n' | sort | paste -sd, -)   # sorted: stable order
    [ -n "$SUBNET_IDS" ] || fail "no default subnets in $VPC_ID: set SUBNET_IDS in config.env"
  fi
  deploy_stack cluster cluster.yaml VpcId="$VPC_ID" SubnetIds="$SUBNET_IDS"
fi

if want rabbitmq; then
  stack_exists secrets || fail "stack $STACK_PREFIX-secrets not found: deploy 'secrets' first"
  stack_exists cluster || fail "stack $STACK_PREFIX-cluster not found: deploy 'cluster' first"
  deploy_stack rabbitmq rabbitmq.yaml AdminCidr="$RABBITMQ_ADMIN_CIDR"
fi

if want observability; then
  stack_exists cluster || fail "stack $STACK_PREFIX-cluster not found: deploy 'cluster' first"
  deploy_stack observability observability.yaml AdminCidr="$JAEGER_ADMIN_CIDR"
fi

if want api; then
  # Separate assignments, so a missing stack stops the script (set -e ignores failures inside arguments).
  ISSUER=$(output cognito Issuer)
  CLIENT_ID=$(output cognito AppClientId)
  SITE_URL=$(output frontend SiteUrl)
  # API Gateway reaches each service through a VPC Link + its Cloud Map service; a service that isn't deployed
  # (or not registered) gets no routes yet.
  stack_exists cluster || fail "stack $STACK_PREFIX-cluster not found: deploy 'cluster' first (the VPC Link uses it)"
  AUTH_ARN=$(optional_output auth DiscoveryServiceArn)
  EVENTS_ARN=$(optional_output events DiscoveryServiceArn)
  PAYMENT_ARN=$(optional_output payment DiscoveryServiceArn)
  NOTIFICATIONS_ARN=$(optional_output notifications DiscoveryServiceArn)
  NOTIFICATIONS_SG=$(optional_output notifications ServiceSecurityGroupId)
  [ -n "$NOTIFICATIONS_SG" ] || NOTIFICATIONS_ARN=""   # its port 5000 must be opened to the VPC Link too
  deploy_stack api api.yaml \
    CognitoIssuer="$ISSUER" \
    CognitoAppClientId="$CLIENT_ID" \
    AllowedOrigin="$SITE_URL" \
    AuthServiceArn="$AUTH_ARN" \
    EventsServiceArn="$EVENTS_ARN" \
    PaymentServiceArn="$PAYMENT_ARN" \
    NotificationsServiceArn="$NOTIFICATIONS_ARN" \
    NotificationsSecurityGroupId="$NOTIFICATIONS_SG"
fi

if want site; then
  API_URL=$(output api ApiUrl)
  CLIENT_ID=$(output cognito AppClientId)
  BUCKET=$(output frontend BucketName)
  DISTRIBUTION=$(output frontend DistributionId)
  echo
  echo "== site: build against $API_URL (Cognito client $CLIENT_ID) and upload to s3://$BUCKET"
  (
    cd "$FRONTEND_DIR"
    npm ci
    # Public values baked into the bundle. Every service is reached through the API Gateway, so
    # VITE_USER_API_URL stays empty (the frontend then uses $API_URL/user/me).
    VITE_API_BASE_URL="$API_URL" \
    VITE_COGNITO_REGION="$AWS_REGION" \
    VITE_COGNITO_CLIENT_ID="$CLIENT_ID" \
    VITE_PAYMENT_API_URL="$API_URL" \
    VITE_USER_API_URL="" \
      npm run build
  )
  # Hashed assets can be cached forever; index.html must always be re-checked so a new deploy shows up at once.
  aws s3 sync "$FRONTEND_DIR/dist/" "s3://$BUCKET/" --delete --exclude index.html \
    --cache-control "public, max-age=31536000, immutable"
  aws s3 cp "$FRONTEND_DIR/dist/index.html" "s3://$BUCKET/index.html" --cache-control "no-cache"
  # MSYS_NO_PATHCONV stops Git Bash on Windows from turning "/*" into a Windows path.
  MSYS_NO_PATHCONV=1 aws cloudfront create-invalidation --distribution-id "$DISTRIBUTION" --paths "/*" \
    --query Invalidation.Id --output text >/dev/null
fi

if want chaos; then
  # The experiments target these services, and the stop condition reads the events and payment logs.
  for stack in cluster rabbitmq events payment; do
    stack_exists "$stack" || fail "stack $STACK_PREFIX-$stack not found: deploy '$stack' first (the experiments target it)"
  done
  deploy_stack chaos chaos.yaml
fi

# show <label> <stack name> <output key>  - prints the output if that stack is deployed
show() {
  if stack_exists "$2"; then printf '  %-14s %s\n' "$1:" "$(output "$2" "$3")"; fi
}

echo
echo "Done ($PARTS)."
show "Frontend" frontend SiteUrl
show "API Gateway" api ApiUrl
show "User pool" cognito UserPoolId
show "App client" cognito AppClientId
show "Issuer" cognito Issuer
show "RabbitMQ" rabbitmq Host
show "OTLP endpoint" observability OtlpEndpoint
echo
echo "Next:"
echo "  - create your admin:    ./add-user.sh admin you@example.com 'YourPassw0rd' admin"
echo "  - set the Stripe sandbox keys in Secrets Manager: $STACK_PREFIX/payment (see README)"
if want cognito && ! want api; then
  echo "  - Cognito changed: deploy 'api' too, so API Gateway's JWT authorizer trusts the current user pool"
fi
if want api; then
  echo "  - API Gateway routes only to the services deployed before 'api' (auth, events, payment): deploy any new one,"
  echo "    then 'api' again"
fi
