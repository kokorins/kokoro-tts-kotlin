#!/usr/bin/env bash
#
# Full AWS deployment for Kokoro TTS (EC2 target).
#
# Provisions all AWS infrastructure (S3 bucket, IAM role, ECR repository,
# EC2 instance), builds the Docker image locally, pushes to ECR, and
# launches the service via user-data + systemd.
#
# No SSH required. All remote operations use AWS Systems Manager (SSM).
#
# Every step checks its result before moving to the next one.
# Re-running is safe — existing resources are detected and skipped.
#
# Prerequisites:
#   - AWS CLI v2 configured with sufficient permissions
#   - Docker installed locally
#   - curl installed locally
#
# Configurable via environment variables:
#   AWS_REGION     (default: eu-central-1)
#   INSTANCE_TYPE  (default: t4g.small)
#
# Usage:
#   ./scripts/deploy-aws.sh
#

set -euo pipefail

# ─── Change to project root ──────────────────────────────────────────
# All paths (Dockerfile, data/, scripts/) are relative to the repo root.

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
# Resource names used throughout the script. The S3 bucket name includes
# the AWS account ID to guarantee global uniqueness.

AWS_REGION="${AWS_REGION:-eu-central-1}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t4g.small}"

ROLE_NAME="tts-service-role"
PROFILE_NAME="tts-service-profile"
SG_NAME="tts-service-sg"
ECR_REPO_NAME="kokoro-tts"

echo -e "${BOLD}Kokoro TTS — Full AWS Deployment (SSM + ECR)${NC}"
echo "Region:        $AWS_REGION"
echo "Instance type: $INSTANCE_TYPE"

# ─── Pre-flight: Docker ─────────────────────────────────────────────
# Docker is required to build the container image locally before pushing
# to ECR. Both the CLI and daemon must be available.

if ! command -v docker &>/dev/null; then
    fail "Docker is not installed. Please install Docker first: https://docs.docker.com/get-docker/"
fi

if ! docker info &>/dev/null; then
    fail "Docker daemon is not running. Please start Docker and try again."
fi

ok "Docker is available ($(docker --version | head -1))"

# ─── Pre-flight: AWS session ────────────────────────────────────────
# Verify that AWS credentials are active. If using SSO and the session
# has expired, attempt to re-login automatically.

if ! aws sts get-caller-identity &>/dev/null; then
    warn "AWS session is not active. Attempting 'aws sso login'..."
    aws sso login
    if ! aws sts get-caller-identity &>/dev/null; then
        fail "AWS session is still not active after login. Run 'aws configure' or check your SSO setup."
    fi
    ok "AWS session is now active"
fi

# =====================================================================
#  S3 SETUP
#  Creates the bucket for storing generated audio files with:
#  - Public read access on the tts-audio/ prefix (for direct download URLs)
#  - AES-256 server-side encryption
#  - 1-day lifecycle expiration (audio files are ephemeral)
# =====================================================================

# ─── 1. Verify AWS CLI credentials ──────────────────────────────────

step "Verify AWS CLI credentials"

ACCOUNT_ID=$(aws sts get-caller-identity \
    --query Account --output text 2>/dev/null) \
    || fail "aws sts get-caller-identity failed."

CALLER_ARN=$(aws sts get-caller-identity --query Arn --output text)
ok "Account $ACCOUNT_ID ($CALLER_ARN)"

# Derive resource names from the account ID
TTS_BUCKET="tts-audio-${ACCOUNT_ID}"
ECR_REPO="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO_NAME}"
echo "    S3 bucket:   $TTS_BUCKET"
echo "    ECR repo:    $ECR_REPO"

# ─── 2. Create S3 bucket ────────────────────────────────────────────
# The bucket stores synthesized audio files under the tts-audio/ prefix.
# LocationConstraint is required for all regions except us-east-1.

step "Create S3 bucket ($TTS_BUCKET)"

if aws s3api head-bucket --bucket "$TTS_BUCKET" 2>/dev/null; then
    ok "Bucket already exists"
else
    aws s3api create-bucket \
        --bucket "$TTS_BUCKET" \
        --region "$AWS_REGION" \
        --create-bucket-configuration LocationConstraint="$AWS_REGION" \
        > /dev/null \
        || fail "Failed to create bucket"

    aws s3api head-bucket --bucket "$TTS_BUCKET" 2>/dev/null \
        || fail "Bucket not reachable after creation"
    ok "Created"
fi

# ─── 3. Configure public access block ───────────────────────────────
# Block ACL-based public access but allow bucket-policy-based public access.
# This lets us grant public read via a bucket policy (step 4) while
# preventing accidental public access through ACLs.

step "Configure public access block (allow bucket policy, block ACLs)"

aws s3api put-public-access-block \
    --bucket "$TTS_BUCKET" \
    --public-access-block-configuration \
        BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=false,RestrictPublicBuckets=false \
    || fail "Failed to set public access block"

# Verify the setting took effect
BLOCK_POLICY=$(aws s3api get-public-access-block --bucket "$TTS_BUCKET" \
    --query "PublicAccessBlockConfiguration.BlockPublicPolicy" \
    --output text 2>/dev/null) || true
if [ "$BLOCK_POLICY" = "False" ]; then
    ok "BlockPublicPolicy=false"
else
    fail "Expected BlockPublicPolicy=False, got $BLOCK_POLICY"
fi

# ─── 4. Apply bucket policy (public read on tts-audio/) ─────────────
# Allow anyone to GET objects under tts-audio/*. This enables the TTS API
# to return direct S3 URLs that clients can download without authentication.

step "Apply bucket policy (public read on tts-audio/ prefix)"

aws s3api put-bucket-policy \
    --bucket "$TTS_BUCKET" \
    --policy '{
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "PublicReadTtsAudio",
                "Effect": "Allow",
                "Principal": "*",
                "Action": "s3:GetObject",
                "Resource": "arn:aws:s3:::'"$TTS_BUCKET"'/tts-audio/*"
            }
        ]
    }' \
    || fail "Failed to apply bucket policy"

POLICY_CHECK=$(aws s3api get-bucket-policy --bucket "$TTS_BUCKET" \
    --output text 2>/dev/null) || true
if echo "$POLICY_CHECK" | grep -q "PublicReadTtsAudio"; then
    ok "Bucket policy applied"
else
    warn "Could not verify bucket policy Sid, but put-bucket-policy succeeded"
fi

# ─── 5. Enable server-side encryption ───────────────────────────────
# Encrypt all objects at rest with AES-256. BucketKeyEnabled reduces
# KMS costs (not applicable to AES256 but good practice).

step "Enable server-side encryption (AES-256)"

aws s3api put-bucket-encryption \
    --bucket "$TTS_BUCKET" \
    --server-side-encryption-configuration '{
        "Rules": [
            {
                "ApplyServerSideEncryptionByDefault": {
                    "SSEAlgorithm": "AES256"
                },
                "BucketKeyEnabled": true
            }
        ]
    }' \
    || fail "Failed to enable encryption"

SSE_ALG=$(aws s3api get-bucket-encryption --bucket "$TTS_BUCKET" \
    --query "ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm" \
    --output text 2>/dev/null) || true
if [ "$SSE_ALG" = "AES256" ]; then
    ok "Encryption enabled ($SSE_ALG)"
else
    fail "Expected AES256, got $SSE_ALG"
fi

# ─── 6. Add lifecycle rule (expire tts-audio/ after 1 day) ──────────
# Audio files are ephemeral — clients download them shortly after synthesis.
# Auto-delete after 1 day to keep storage costs near zero.

step "Add lifecycle rule (expire tts-audio/ after 1 day)"

aws s3api put-bucket-lifecycle-configuration \
    --bucket "$TTS_BUCKET" \
    --lifecycle-configuration '{
        "Rules": [
            {
                "ID": "expire-tts-audio",
                "Filter": { "Prefix": "tts-audio/" },
                "Status": "Enabled",
                "Expiration": { "Days": 1 }
            }
        ]
    }' \
    || fail "Failed to set lifecycle rule"

LC_STATUS=$(aws s3api get-bucket-lifecycle-configuration --bucket "$TTS_BUCKET" \
    --query "Rules[?ID=='expire-tts-audio'].Status | [0]" \
    --output text 2>/dev/null) || true
if [ "$LC_STATUS" = "Enabled" ]; then
    ok "Lifecycle rule active (1 day expiration)"
else
    fail "Expected lifecycle status Enabled, got $LC_STATUS"
fi

# ─── 7. Test S3 public access round-trip ────────────────────────────
# Upload a test file, verify it's publicly accessible via HTTP, then clean up.
# This catches misconfigured bucket policies or public access blocks early.

step "Test S3 public access round-trip"

echo "deploy-test" > /tmp/tts-deploy-test.txt
aws s3 cp /tmp/tts-deploy-test.txt \
    "s3://$TTS_BUCKET/tts-audio/deploy-test.txt" \
    --region "$AWS_REGION" > /dev/null \
    || fail "Upload to S3 failed"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    "https://$TTS_BUCKET.s3.$AWS_REGION.amazonaws.com/tts-audio/deploy-test.txt") || true

# Clean up the test file regardless of result
aws s3 rm "s3://$TTS_BUCKET/tts-audio/deploy-test.txt" \
    --region "$AWS_REGION" > /dev/null 2>&1 || true
rm -f /tmp/tts-deploy-test.txt

if [ "$HTTP_CODE" = "200" ]; then
    ok "Upload + public download verified (HTTP $HTTP_CODE)"
else
    fail "Public download returned HTTP $HTTP_CODE (expected 200)"
fi

# =====================================================================
#  IAM SETUP
#  Creates an IAM role for the EC2 instance with:
#  - S3 PutObject on the tts-audio/ prefix (for storing generated audio)
#  - SSM managed policy (for remote management without SSH)
#  - ECR pull permissions (for pulling the Docker image from ECR)
# =====================================================================

# ─── 8. Create IAM role ─────────────────────────────────────────────
# The trust policy allows EC2 instances to assume this role.
# Policies are attached in the following steps.

step "Create IAM role ($ROLE_NAME)"

TRUST_POLICY='{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": { "Service": "ec2.amazonaws.com" },
            "Action": "sts:AssumeRole"
        }
    ]
}'

if aws iam get-role --role-name "$ROLE_NAME" \
    --query "Role.RoleName" --output text 2>/dev/null \
    | grep -q "$ROLE_NAME"; then
    ok "Role already exists"
else
    aws iam create-role \
        --role-name "$ROLE_NAME" \
        --assume-role-policy-document "$TRUST_POLICY" \
        --description "Role for TTS service EC2 instances" \
        > /dev/null \
        || fail "Failed to create role"

    aws iam get-role --role-name "$ROLE_NAME" > /dev/null 2>&1 \
        || fail "Role not found after creation"
    ok "Created"
fi

# ─── 9. Attach S3 PutObject policy ──────────────────────────────────
# The TTS service uploads synthesized audio to S3. This inline policy
# grants PutObject only on the tts-audio/ prefix (least privilege).

step "Attach S3 write policy (PutObject on $TTS_BUCKET/tts-audio/*)"

S3_POLICY=$(cat <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowTtsAudioWrite",
            "Effect": "Allow",
            "Action": "s3:PutObject",
            "Resource": "arn:aws:s3:::${TTS_BUCKET}/tts-audio/*"
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

POLICY_NAME=$(aws iam get-role-policy \
    --role-name "$ROLE_NAME" --policy-name tts-s3-access \
    --query "PolicyName" --output text 2>/dev/null) || true
if [ "$POLICY_NAME" = "tts-s3-access" ]; then
    ok "Policy tts-s3-access attached"
else
    fail "Policy verification failed (got $POLICY_NAME)"
fi

# ─── 10. Attach SSM managed policy ──────────────────────────────────
# AmazonSSMManagedInstanceCore enables Systems Manager access to the
# EC2 instance, allowing remote command execution without SSH keys.

step "Attach SSM managed policy (AmazonSSMManagedInstanceCore)"

aws iam attach-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore \
    || fail "Failed to attach SSM policy"

SSM_ATTACHED=$(aws iam list-attached-role-policies \
    --role-name "$ROLE_NAME" \
    --query "AttachedPolicies[?PolicyName=='AmazonSSMManagedInstanceCore'].PolicyName | [0]" \
    --output text 2>/dev/null) || true
if [ "$SSM_ATTACHED" = "AmazonSSMManagedInstanceCore" ]; then
    ok "AmazonSSMManagedInstanceCore attached"
else
    fail "SSM policy not found after attachment (got $SSM_ATTACHED)"
fi

# ─── 11. Attach ECR pull policy ─────────────────────────────────────
# The EC2 instance needs to authenticate with ECR and pull the Docker
# image during boot (user-data) and on redeployments.

step "Attach ECR pull policy"

ECR_POLICY=$(cat <<'EOF'
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowEcrPull",
            "Effect": "Allow",
            "Action": [
                "ecr:GetAuthorizationToken",
                "ecr:BatchGetImage",
                "ecr:GetDownloadUrlForLayer",
                "ecr:BatchCheckLayerAvailability"
            ],
            "Resource": "*"
        }
    ]
}
EOF
)

aws iam put-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-name ecr-pull-access \
    --policy-document "$ECR_POLICY" \
    || fail "Failed to attach ECR policy"

ECR_POLICY_NAME=$(aws iam get-role-policy \
    --role-name "$ROLE_NAME" --policy-name ecr-pull-access \
    --query "PolicyName" --output text 2>/dev/null) || true
if [ "$ECR_POLICY_NAME" = "ecr-pull-access" ]; then
    ok "Policy ecr-pull-access attached"
else
    fail "ECR policy verification failed (got $ECR_POLICY_NAME)"
fi

# ─── 12. Create instance profile ────────────────────────────────────
# EC2 instances can't use IAM roles directly — they need an instance
# profile that wraps the role. The profile is referenced when launching.

step "Create instance profile ($PROFILE_NAME)"

if aws iam get-instance-profile --instance-profile-name "$PROFILE_NAME" \
    > /dev/null 2>&1; then
    ok "Instance profile already exists"
else
    aws iam create-instance-profile \
        --instance-profile-name "$PROFILE_NAME" > /dev/null \
        || fail "Failed to create instance profile"

    # Attach the IAM role to the instance profile
    aws iam add-role-to-instance-profile \
        --instance-profile-name "$PROFILE_NAME" \
        --role-name "$ROLE_NAME" \
        || fail "Failed to attach role to profile"

    PROFILE_ROLE=$(aws iam get-instance-profile \
        --instance-profile-name "$PROFILE_NAME" \
        --query "InstanceProfile.Roles[0].RoleName" --output text 2>/dev/null) || true
    if [ "$PROFILE_ROLE" = "$ROLE_NAME" ]; then
        ok "Created with role $ROLE_NAME"
    else
        fail "Profile role is $PROFILE_ROLE, expected $ROLE_NAME"
    fi
fi

# =====================================================================
#  ECR SETUP + DOCKER BUILD
#  Builds the Ktor/Netty fat-JAR Docker image for linux/arm64 (Graviton)
#  and pushes it to ECR. The EC2 instance pulls from ECR during boot.
# =====================================================================

# ─── 13. Create ECR repository ──────────────────────────────────────

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

    aws ecr describe-repositories \
        --repository-names "$ECR_REPO_NAME" \
        --region "$AWS_REGION" > /dev/null 2>&1 \
        || fail "Repository not found after creation"
    ok "Created"
fi

# ─── 14. Build Docker image locally ─────────────────────────────────
# Multi-stage build: downloads data files, compiles the fat JAR, and
# produces a JRE 25 run image. Target platform is linux/arm64 for
# Graviton (t4g/c7g) instances.

step "Build Docker image locally"

if [ ! -f "Dockerfile" ]; then
    fail "Dockerfile not found in current directory. Run from project root."
fi

echo "    Building image for linux/arm64 (first build takes several minutes)..."
docker build --platform linux/arm64 -t kokoro-tts:latest . \
    || fail "Docker build failed"

docker images kokoro-tts:latest --format '{{.Repository}}' 2>/dev/null \
    | grep -q "kokoro-tts" \
    || fail "Image not found after build"
ok "Image kokoro-tts:latest built"

# ─── 15. Push Docker image to ECR ───────────────────────────────────
# Authenticate with ECR (token valid for 12 hours), tag the local image
# with the full ECR URI, and push. First push uploads ~500 MB; subsequent
# pushes only upload changed layers.

step "Push Docker image to ECR"

echo "    Logging into ECR..."
aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin \
        "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com" \
    || fail "ECR login failed"

docker tag kokoro-tts:latest "$ECR_REPO:latest" \
    || fail "Docker tag failed"

echo "    Pushing image (first push uploads ~500 MB)..."
docker push "$ECR_REPO:latest" \
    || fail "Docker push failed"

ok "Pushed to $ECR_REPO:latest"

# =====================================================================
#  EC2 SETUP (SSM, no SSH)
#  Launches an ARM64 EC2 instance with a user-data script that:
#  1. Installs Docker
#  2. Pulls the image from ECR
#  3. Creates a systemd service that runs the TTS container on port 80
#  No SSH key is attached — use SSM for remote access.
# =====================================================================

# ─── 16. Ensure security group (HTTP only) ──────────────────────────
# Only port 80 (HTTP) is open. No SSH (port 22) — use SSM instead.
# The security group is created in the default VPC.

step "Ensure security group ($SG_NAME — HTTP only, no SSH)"

SG_ID=$(aws ec2 describe-security-groups \
    --filters "Name=group-name,Values=$SG_NAME" \
    --region "$AWS_REGION" \
    --query "SecurityGroups[0].GroupId" --output text 2>/dev/null) || true

if [ -n "$SG_ID" ] && [ "$SG_ID" != "None" ]; then
    ok "Already exists ($SG_ID)"
else
    VPC_ID=$(aws ec2 describe-vpcs \
        --filters "Name=isDefault,Values=true" \
        --region "$AWS_REGION" \
        --query "Vpcs[0].VpcId" --output text) \
        || fail "Failed to find default VPC"

    SG_ID=$(aws ec2 create-security-group \
        --group-name "$SG_NAME" \
        --description "Kokoro TTS - HTTP only" \
        --vpc-id "$VPC_ID" \
        --region "$AWS_REGION" \
        --query "GroupId" --output text) \
        || fail "Failed to create security group"

    # Allow HTTP (80) only — no SSH needed, use SSM instead
    aws ec2 authorize-security-group-ingress \
        --group-id "$SG_ID" --protocol tcp --port 80 --cidr 0.0.0.0/0 \
        --region "$AWS_REGION" > /dev/null 2>&1 || true

    SG_CHECK=$(aws ec2 describe-security-groups --group-ids "$SG_ID" \
        --region "$AWS_REGION" \
        --query "SecurityGroups[0].GroupId" --output text 2>/dev/null) || true
    if [ "$SG_CHECK" = "$SG_ID" ]; then
        ok "Created $SG_ID (HTTP only)"
    else
        fail "Security group not found after creation"
    fi
fi

# ─── 17. Launch EC2 instance with user-data ──────────────────────────
# Finds the latest Amazon Linux 2023 ARM64 AMI and launches an instance
# with a user-data script that bootstraps Docker + the TTS container.
# If an old SSH-based instance exists, it's terminated and replaced.

step "Launch EC2 instance ($INSTANCE_TYPE) with user-data bootstrap"

# Check for an existing instance tagged "kokoro-tts"
EXISTING_INSTANCE=$(aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=kokoro-tts" \
              "Name=instance-state-name,Values=running,pending" \
    --region "$AWS_REGION" \
    --query "Reservations[0].Instances[0].InstanceId" --output text 2>/dev/null) || true

# If the existing instance was launched with an SSH key, it's from the old
# SSH-based deployment and won't have user-data bootstrap or working SSM.
# Terminate it and launch fresh with the SSM-based setup.
if [ -n "$EXISTING_INSTANCE" ] && [ "$EXISTING_INSTANCE" != "None" ]; then
    OLD_KEY=$(aws ec2 describe-instances \
        --instance-ids "$EXISTING_INSTANCE" --region "$AWS_REGION" \
        --query "Reservations[0].Instances[0].KeyName" --output text 2>/dev/null) || true
    if [ -n "$OLD_KEY" ] && [ "$OLD_KEY" != "None" ]; then
        warn "Existing instance $EXISTING_INSTANCE was launched with SSH key ($OLD_KEY)"
        echo "    Terminating old SSH-based instance to relaunch with user-data + SSM..."
        aws ec2 terminate-instances --instance-ids "$EXISTING_INSTANCE" \
            --region "$AWS_REGION" > /dev/null \
            || fail "Failed to terminate old instance"
        aws ec2 wait instance-terminated --instance-ids "$EXISTING_INSTANCE" \
            --region "$AWS_REGION" 2>/dev/null || true
        ok "Terminated $EXISTING_INSTANCE"
        EXISTING_INSTANCE=""
    fi
fi

if [ -n "$EXISTING_INSTANCE" ] && [ "$EXISTING_INSTANCE" != "None" ]; then
    INSTANCE_ID="$EXISTING_INSTANCE"
    ok "Already running ($INSTANCE_ID)"
else
    # IAM instance profiles take a few seconds to propagate after creation
    echo "    Waiting 10s for instance profile propagation..."
    sleep 10

    # Find the latest Amazon Linux 2023 ARM64 AMI
    AMI_ID=$(aws ec2 describe-images \
        --owners amazon \
        --filters "Name=name,Values=al2023-ami-2023.*-arm64" \
                  "Name=state,Values=available" \
        --query "Images | sort_by(@, &CreationDate) | [-1].ImageId" \
        --output text --region "$AWS_REGION") \
        || fail "Failed to find Amazon Linux 2023 AMI"

    echo "    AMI: $AMI_ID"

    # User-data script runs on first boot (cloud-init). It:
    # 1. Installs Docker from the AL2023 repos
    # 2. Authenticates with ECR and pulls the TTS image
    # 3. Creates a systemd service that maps container port 8080 → host port 80
    cat > /tmp/kokoro-user-data.sh << USERDATA
#!/bin/bash
set -e

# Install Docker
dnf install -y docker
systemctl start docker
systemctl enable docker
usermod -aG docker ec2-user

# Log into ECR and pull the image
aws ecr get-login-password --region ${AWS_REGION} | \
    docker login --username AWS --password-stdin ${ECR_REPO}

docker pull ${ECR_REPO}:latest
docker tag ${ECR_REPO}:latest kokoro-tts:latest

# Create the systemd service for auto-start on boot and easy restart
cat > /etc/systemd/system/kokoro-tts.service << 'UNIT'
[Unit]
Description=Kokoro TTS Service
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStartPre=-/usr/bin/docker stop kokoro-tts
ExecStartPre=-/usr/bin/docker rm kokoro-tts
ExecStart=/usr/bin/docker run -d \
    --name kokoro-tts \
    --restart unless-stopped \
    -p 80:8080 \
    -e S3_BUCKET=${TTS_BUCKET} \
    -e AWS_REGION=${AWS_REGION} \
    kokoro-tts:latest
ExecStop=/usr/bin/docker stop kokoro-tts

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable kokoro-tts
systemctl start kokoro-tts
USERDATA

    # Launch the instance (no SSH key — SSM only)
    INSTANCE_ID=$(aws ec2 run-instances \
        --image-id "$AMI_ID" \
        --instance-type "$INSTANCE_TYPE" \
        --iam-instance-profile Name="$PROFILE_NAME" \
        --security-group-ids "$SG_ID" \
        --user-data file:///tmp/kokoro-user-data.sh \
        --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=kokoro-tts}]' \
        --region "$AWS_REGION" \
        --query "Instances[0].InstanceId" --output text) \
        || fail "Failed to launch instance"

    rm -f /tmp/kokoro-user-data.sh
    ok "Launched $INSTANCE_ID"
fi

# ─── 18. Wait for instance to be running ────────────────────────────
# The instance transitions: pending → running. This usually takes 30-60s.

step "Wait for instance $INSTANCE_ID to be running"

aws ec2 wait instance-running \
    --instance-ids "$INSTANCE_ID" --region "$AWS_REGION" \
    || fail "Instance did not reach running state"

PUBLIC_IP=$(aws ec2 describe-instances \
    --instance-ids "$INSTANCE_ID" --region "$AWS_REGION" \
    --query "Reservations[0].Instances[0].PublicIpAddress" --output text) || true

if [ -n "$PUBLIC_IP" ] && [ "$PUBLIC_IP" != "None" ]; then
    ok "Running at $PUBLIC_IP"
else
    fail "Instance has no public IP. Enable auto-assign or attach an Elastic IP."
fi

# ─── 19. Wait for SSM registration ──────────────────────────────────
# The SSM agent (pre-installed on AL2023) needs the IAM role to be active
# before it can register with Systems Manager. Polls every 10s for up to 5 min.

step "Wait for SSM agent to register ($INSTANCE_ID)"

echo "    SSM agent needs IAM permissions + a minute to register..."
for attempt in $(seq 1 30); do
    SSM_STATUS=$(aws ssm describe-instance-information \
        --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
        --region "$AWS_REGION" \
        --query "InstanceInformationList[0].PingStatus" \
        --output text 2>/dev/null) || true
    if [ "$SSM_STATUS" = "Online" ]; then
        ok "SSM agent online (attempt $attempt)"
        break
    fi
    if [ "$attempt" -eq 30 ]; then
        fail "SSM agent not online after 30 attempts (5 min). Check IAM role has AmazonSSMManagedInstanceCore."
    fi
    sleep 10
done

# ─── 20. Wait for user-data to complete ─────────────────────────────
# cloud-init creates /var/lib/cloud/instance/boot-finished when user-data
# completes. We poll for this file via SSM every 10s.

step "Wait for user-data bootstrap to complete"

echo "    User-data installs Docker, pulls image, starts service..."
echo "    This takes 2-5 minutes on first boot."

for attempt in $(seq 1 30); do
    # Check if cloud-init has finished by looking for the boot-finished marker
    COMMAND_ID=$(aws ssm send-command \
        --instance-ids "$INSTANCE_ID" \
        --document-name "AWS-RunShellScript" \
        --parameters 'commands=["test -f /var/lib/cloud/instance/boot-finished && echo DONE || echo WAITING"]' \
        --region "$AWS_REGION" \
        --query "Command.CommandId" --output text 2>/dev/null) || true

    if [ -n "$COMMAND_ID" ] && [ "$COMMAND_ID" != "None" ]; then
        sleep 3
        BOOT_STATUS=$(aws ssm get-command-invocation \
            --instance-id "$INSTANCE_ID" \
            --command-id "$COMMAND_ID" \
            --region "$AWS_REGION" \
            --query "StandardOutputContent" --output text 2>/dev/null) || true

        if echo "$BOOT_STATUS" | grep -q "DONE"; then
            ok "User-data finished (attempt $attempt)"
            break
        fi
    fi
    if [ "$attempt" -eq 30 ]; then
        fail "User-data did not complete after 30 attempts (5 min)"
    fi
    sleep 10
done

# ─── 21. Verify container is running via SSM ────────────────────────
# Run "docker ps" on the instance via SSM to confirm the TTS container
# started successfully. If not found, fetch cloud-init logs for debugging.

step "Verify container is running via SSM"

COMMAND_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --parameters 'commands=["docker ps --format \"{{.Names}} {{.Status}}\""]' \
    --region "$AWS_REGION" \
    --query "Command.CommandId" --output text) \
    || fail "Failed to send SSM command"

sleep 5

CONTAINER_STATUS=$(aws ssm get-command-invocation \
    --instance-id "$INSTANCE_ID" \
    --command-id "$COMMAND_ID" \
    --region "$AWS_REGION" \
    --query "StandardOutputContent" --output text 2>/dev/null) || true

if echo "$CONTAINER_STATUS" | grep -q "kokoro-tts"; then
    ok "Container running: $CONTAINER_STATUS"
else
    # Container didn't start — fetch cloud-init logs for debugging
    warn "Container not found in docker ps. Checking cloud-init logs..."
    ERR_CMD_ID=$(aws ssm send-command \
        --instance-ids "$INSTANCE_ID" \
        --document-name "AWS-RunShellScript" \
        --parameters 'commands=["tail -30 /var/log/cloud-init-output.log"]' \
        --region "$AWS_REGION" \
        --query "Command.CommandId" --output text 2>/dev/null) || true
    if [ -n "$ERR_CMD_ID" ] && [ "$ERR_CMD_ID" != "None" ]; then
        sleep 5
        aws ssm get-command-invocation \
            --instance-id "$INSTANCE_ID" \
            --command-id "$ERR_CMD_ID" \
            --region "$AWS_REGION" \
            --query "StandardOutputContent" --output text 2>/dev/null || true
    fi
    fail "Container kokoro-tts not running"
fi

# ─── 22. Wait for health endpoint ───────────────────────────────────
# The Ktor server takes 10-30s to load the ONNX model and bind the port.
# Poll the /health endpoint until it returns "OK".

step "Wait for health endpoint"

for attempt in $(seq 1 30); do
    HEALTH=$(curl -sf --max-time 5 "http://$PUBLIC_IP/health" 2>/dev/null) || true
    if [ "$HEALTH" = "OK" ]; then
        ok "Health returned OK (attempt $attempt)"
        break
    fi
    if [ "$attempt" -eq 30 ]; then
        # Fetch container logs via SSM for debugging
        ERR_CMD_ID=$(aws ssm send-command \
            --instance-ids "$INSTANCE_ID" \
            --document-name "AWS-RunShellScript" \
            --parameters 'commands=["docker logs --tail 30 kokoro-tts"]' \
            --region "$AWS_REGION" \
            --query "Command.CommandId" --output text 2>/dev/null) || true
        if [ -n "$ERR_CMD_ID" ] && [ "$ERR_CMD_ID" != "None" ]; then
            sleep 5
            aws ssm get-command-invocation \
                --instance-id "$INSTANCE_ID" \
                --command-id "$ERR_CMD_ID" \
                --region "$AWS_REGION" \
                --query "StandardOutputContent" --output text 2>/dev/null || true
        fi
        fail "Health endpoint not responding after 30 attempts"
    fi
    sleep 10
done

# ─── Done ────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}${GREEN}Deployment complete!${NC}"
echo ""
echo "  Instance ID:  $INSTANCE_ID"
echo "  Public IP:    $PUBLIC_IP"
echo "  S3 bucket:    $TTS_BUCKET"
echo "  ECR repo:     $ECR_REPO"
echo ""
echo "  Health:       http://$PUBLIC_IP/health"
echo "  Voices:       http://$PUBLIC_IP/v1/voices"
echo "  TTS API:      http://$PUBLIC_IP/v1/tts"
echo ""
echo "  SSM session:  aws ssm start-session --target $INSTANCE_ID"
echo "  Logs:         aws ssm send-command --instance-ids $INSTANCE_ID \\"
echo "                  --document-name AWS-RunShellScript \\"
echo "                  --parameters 'commands=[\"docker logs --tail 50 kokoro-tts\"]'"
echo ""
echo "Redeploy (after code changes):"
echo "  docker build --platform linux/arm64 -t kokoro-tts:latest . && \\"
echo "  docker tag kokoro-tts:latest $ECR_REPO:latest && \\"
echo "  aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com && \\"
echo "  docker push $ECR_REPO:latest && \\"
echo "  aws ssm send-command --instance-ids $INSTANCE_ID \\"
echo "    --document-name AWS-RunShellScript \\"
echo "    --parameters commands='[\"aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REPO\",\"docker pull $ECR_REPO:latest\",\"docker tag $ECR_REPO:latest kokoro-tts:latest\",\"systemctl restart kokoro-tts\"]'"
echo ""
echo "Test:"
echo "  curl -X POST http://$PUBLIC_IP/v1/tts \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"turns\": [{\"voice\": \"af_heart\", \"text\": \"Hello from Kokoro TTS!\"}]}'"
