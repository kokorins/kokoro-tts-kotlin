#!/usr/bin/env bash
#
# AWS Lambda deployment for Kokoro TTS.
#
# Builds a container image with the Lambda handler, pushes to ECR,
# and creates/updates the Lambda function with a Function URL.
#
# Uses a custom container image (JDK 25) because AWS managed Java
# runtimes only go up to JDK 21. The image includes the Lambda
# Runtime Interface Client (RIC) for production and the Runtime
# Interface Emulator (RIE) for local testing.
#
# The same S3 bucket, ONNX model, and voice data are shared with
# the EC2 deployment — only the HTTP layer differs.
#
# Re-running is safe — existing resources are detected and updated.
#
# Prerequisites:
#   - AWS CLI v2 configured with sufficient permissions
#   - Docker with buildx support installed locally
#   - Data files present in data/ (run scripts/download-data.sh first)
#
# Configurable via environment variables:
#   AWS_REGION     (default: eu-central-1)
#   S3_BUCKET      (default: tts-audio-<account-id>)
#
# Usage:
#   ./scripts/deploy-lambda.sh
#

set -euo pipefail

# ─── Change to project root ──────────────────────────────────────────
# All paths (Dockerfile.lambda, data/, scripts/) are relative to the repo root.

cd "$(dirname "$0")/.."

# ─── Output helpers ──────────────────────────────────────────────────
# Colored step/ok/fail/warn functions for readable deployment output.

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

STEP=0

step() {
    STEP=$((STEP + 1))
    echo ""
    echo -e "${BOLD}[${STEP}] $1${NC}"
}

ok()   { echo -e "    ${GREEN}OK:${NC} $1"; }
fail() { echo -e "    ${RED}FAILED:${NC} $1" >&2; exit 1; }
warn() { echo -e "    ${YELLOW}WARN:${NC} $1"; }

# ─── Configuration ───────────────────────────────────────────────────
# Resource names for the Lambda deployment. Uses a separate ECR repo
# and IAM role from the EC2 deployment to keep them independent.

AWS_REGION="${AWS_REGION:-eu-central-1}"
FUNCTION_NAME="kokoro-tts-lambda"
ECR_REPO_NAME="kokoro-tts-lambda"
ROLE_NAME="tts-lambda-role"

echo -e "${BOLD}Kokoro TTS — Lambda Deployment${NC}"
echo "Region: $AWS_REGION"

# ─── Pre-flight checks ──────────────────────────────────────────────
# Verify Docker and AWS credentials are available before doing any work.

step "Pre-flight checks"

if ! command -v docker &>/dev/null; then
    fail "Docker is not installed."
fi

if ! docker info &>/dev/null; then
    fail "Docker daemon is not running."
fi

ok "Docker is available"

# Verify AWS credentials; attempt SSO login if session has expired
if ! aws sts get-caller-identity &>/dev/null; then
    warn "AWS session is not active. Attempting 'aws sso login'..."
    aws sso login
    if ! aws sts get-caller-identity &>/dev/null; then
        fail "AWS session is still not active."
    fi
fi

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ok "AWS account $ACCOUNT_ID"

# Derive resource names from the account ID
S3_BUCKET="${S3_BUCKET:-tts-audio-${ACCOUNT_ID}}"
ECR_REPO="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO_NAME}"
echo "    S3 bucket:   $S3_BUCKET"
echo "    ECR repo:    $ECR_REPO"

# ─── Create ECR repository ──────────────────────────────────────────
# Separate ECR repo from the EC2 deployment (kokoro-tts-lambda vs kokoro-tts)
# because the images have different entrypoints and dependencies.

step "Create ECR repository ($ECR_REPO_NAME)"

if aws ecr describe-repositories \
    --repository-names "$ECR_REPO_NAME" \
    --region "$AWS_REGION" > /dev/null 2>&1; then
    ok "Repository already exists"
else
    aws ecr create-repository \
        --repository-name "$ECR_REPO_NAME" \
        --region "$AWS_REGION" > /dev/null \
        || fail "Failed to create ECR repository"
    ok "Created"
fi

# ─── Build Docker image ─────────────────────────────────────────────
# Multi-stage build: JDK 25 compiles the shadow JAR, then JRE 25 runs it.
# --provenance=false prevents OCI manifest format which Lambda rejects.
# --platform linux/arm64 targets Graviton (cheaper Lambda pricing).
# --load imports the image into the local Docker daemon for tagging/pushing.

step "Build Docker image (Dockerfile.lambda)"

echo "    Building for linux/arm64..."
docker buildx build -f Dockerfile.lambda --platform linux/arm64 \
    --provenance=false --sbom=false \
    --load -t "$FUNCTION_NAME:latest" . \
    || fail "Docker build failed"

ok "Image built"

# ─── Push to ECR ─────────────────────────────────────────────────────
# Authenticate with ECR (token valid for 12 hours), tag, and push.
# Subsequent pushes only upload changed layers.

step "Push Docker image to ECR"

aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin \
        "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com" \
    || fail "ECR login failed"

docker tag "$FUNCTION_NAME:latest" "$ECR_REPO:latest"
docker push "$ECR_REPO:latest" || fail "Docker push failed"

IMAGE_DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "$ECR_REPO:latest" 2>/dev/null) || true
ok "Pushed to $ECR_REPO:latest"

# ─── Create IAM execution role ──────────────────────────────────────
# Lambda execution role with:
# - AWSLambdaBasicExecutionRole: CloudWatch Logs permissions
# - tts-s3-access: PutObject on the S3 bucket's tts-audio/ prefix
# Note: AWS_REGION is automatically set by the Lambda runtime, so we
# don't need to pass it as an environment variable.

step "Create IAM execution role ($ROLE_NAME)"

# Trust policy allows the Lambda service to assume this role
TRUST_POLICY='{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": { "Service": "lambda.amazonaws.com" },
            "Action": "sts:AssumeRole"
        }
    ]
}'

if aws iam get-role --role-name "$ROLE_NAME" > /dev/null 2>&1; then
    ok "Role already exists"
else
    aws iam create-role \
        --role-name "$ROLE_NAME" \
        --assume-role-policy-document "$TRUST_POLICY" \
        --description "Execution role for Kokoro TTS Lambda" \
        > /dev/null \
        || fail "Failed to create role"
    ok "Created"
fi

# CloudWatch Logs access (required for Lambda to write logs)
aws iam attach-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole \
    2>/dev/null || true

# S3 write access for storing synthesized audio files
S3_POLICY=$(cat <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowTtsAudioWrite",
            "Effect": "Allow",
            "Action": "s3:PutObject",
            "Resource": "arn:aws:s3:::${S3_BUCKET}/tts-audio/*"
        }
    ]
}
EOF
)

aws iam put-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-name tts-s3-access \
    --policy-document "$S3_POLICY" \
    || fail "Failed to attach S3 policy"

ok "Policies attached"

ROLE_ARN=$(aws iam get-role --role-name "$ROLE_NAME" \
    --query "Role.Arn" --output text)

# ─── Create/update Lambda function ──────────────────────────────────
# Container-image Lambda with:
# - 3008 MB memory (default Lambda quota maximum; ONNX model + long audio buffers)
# - 900s timeout (15 min — AWS Lambda maximum; long dialogues need this)
# - arm64 architecture (Graviton — 20% cheaper than x86)
# Environment variables configure data file paths and the S3 bucket.
# AWS_REGION is a reserved variable set automatically by Lambda.

step "Create/update Lambda function ($FUNCTION_NAME)"

# IAM role propagation can take a few seconds after creation
sleep 5

if aws lambda get-function --function-name "$FUNCTION_NAME" \
    --region "$AWS_REGION" > /dev/null 2>&1; then
    # Update existing function: first update code (image), then config
    echo "    Updating existing function..."
    aws lambda update-function-code \
        --function-name "$FUNCTION_NAME" \
        --image-uri "$ECR_REPO:latest" \
        --region "$AWS_REGION" > /dev/null \
        || fail "Failed to update function code"

    # Must wait for code update to finish before updating configuration
    aws lambda wait function-updated \
        --function-name "$FUNCTION_NAME" \
        --region "$AWS_REGION" 2>/dev/null || sleep 10

    # Note: --architectures cannot be changed after creation
    aws lambda update-function-configuration \
        --function-name "$FUNCTION_NAME" \
        --memory-size 3008 \
        --timeout 900 \
        --environment "Variables={S3_BUCKET=$S3_BUCKET,TTS_TOKENIZER_CONFIG_PATH=data/config.json,TTS_VOICES_PATH=data/voices-v1.0.bin,TTS_GOLD_DICT_PATH=data/us_gold.json,TTS_SILVER_DICT_PATH=data/us_silver.json,TTS_GB_GOLD_DICT_PATH=data/gb_gold.json,TTS_GB_SILVER_DICT_PATH=data/gb_silver.json,TTS_ONNX_MODEL_PATH=data/kokoro-v1.0.int8.onnx}" \
        --region "$AWS_REGION" > /dev/null \
        || fail "Failed to update function configuration"
    ok "Updated"
else
    # Create new function from the container image
    echo "    Creating new function..."
    aws lambda create-function \
        --function-name "$FUNCTION_NAME" \
        --package-type Image \
        --code "ImageUri=$ECR_REPO:latest" \
        --role "$ROLE_ARN" \
        --architectures arm64 \
        --memory-size 3008 \
        --timeout 900 \
        --environment "Variables={S3_BUCKET=$S3_BUCKET,TTS_TOKENIZER_CONFIG_PATH=data/config.json,TTS_VOICES_PATH=data/voices-v1.0.bin,TTS_GOLD_DICT_PATH=data/us_gold.json,TTS_SILVER_DICT_PATH=data/us_silver.json,TTS_GB_GOLD_DICT_PATH=data/gb_gold.json,TTS_GB_SILVER_DICT_PATH=data/gb_silver.json,TTS_ONNX_MODEL_PATH=data/kokoro-v1.0.int8.onnx}" \
        --region "$AWS_REGION" > /dev/null \
        || fail "Failed to create function"
    ok "Created"
fi

# Wait for the function to transition from Pending to Active
echo "    Waiting for function to become active..."
aws lambda wait function-active-v2 \
    --function-name "$FUNCTION_NAME" \
    --region "$AWS_REGION" 2>/dev/null || sleep 15

# ─── Create Function URL ────────────────────────────────────────────
# Function URLs provide a dedicated HTTPS endpoint without needing
# API Gateway — simpler setup and no additional cost.
# Auth type NONE means the URL is publicly accessible (same as the EC2 setup).

step "Create Function URL"

FUNCTION_URL=$(aws lambda get-function-url-config \
    --function-name "$FUNCTION_NAME" \
    --region "$AWS_REGION" \
    --query "FunctionUrl" --output text 2>/dev/null) || true

if [ -n "$FUNCTION_URL" ] && [ "$FUNCTION_URL" != "None" ]; then
    ok "Function URL already exists: $FUNCTION_URL"
else
    FUNCTION_URL=$(aws lambda create-function-url-config \
        --function-name "$FUNCTION_NAME" \
        --auth-type NONE \
        --region "$AWS_REGION" \
        --query "FunctionUrl" --output text) \
        || fail "Failed to create Function URL"

    # Grant public invoke permission for the Function URL
    aws lambda add-permission \
        --function-name "$FUNCTION_NAME" \
        --statement-id FunctionURLAllowPublicAccess \
        --action lambda:InvokeFunctionUrl \
        --principal "*" \
        --function-url-auth-type NONE \
        --region "$AWS_REGION" > /dev/null 2>&1 || true

    ok "Created: $FUNCTION_URL"
fi

# ─── Done ────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}${GREEN}Lambda deployment complete!${NC}"
echo ""
echo "  Function:     $FUNCTION_NAME"
echo "  Function URL: $FUNCTION_URL"
echo "  S3 bucket:    $S3_BUCKET"
echo "  ECR repo:     $ECR_REPO"
echo ""
echo "Test:"
echo "  curl ${FUNCTION_URL}health"
echo "  curl ${FUNCTION_URL}v1/voices"
echo "  curl -X POST ${FUNCTION_URL}v1/tts \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"turns\": [{\"voice\": \"af_heart\", \"text\": \"Hello from Lambda!\"}]}'"
