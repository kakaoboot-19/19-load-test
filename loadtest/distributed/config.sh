#!/bin/bash
# Configuration for distributed load testing

# AWS 설정
export AWS_REGION="${AWS_REGION:-ap-northeast-2}"
export AWS_PROFILE="${AWS_PROFILE:-goorm-ktb-019}"
export AMI_ID="${AMI_ID:-ami-0aa5df7ab187b76b5}"
export VPC_ID="${VPC_ID:-vpc-039e4008af90d106a}"
export SUBNET_ID="${SUBNET_ID:-subnet-04c91234bf67dfba2}"

# SSH 설정 (기존 키 사용)
export SSH_KEY="/Users/mika/Documents/loadtest/loadtest.pem"
export SSH_USER="${SSH_USER:-ubuntu}"

# EC2 설정
export DEFAULT_INSTANCE_TYPE="${DEFAULT_INSTANCE_TYPE:-t3.small}"
export DEFAULT_KEY_NAME="loadtest"
export DEFAULT_SECURITY_GROUP="${DEFAULT_SECURITY_GROUP:-sg-018993f30d38a64af}"  # 비워두면 기존 것 사용

# 원격 디렉토리
export REMOTE_DIR="~/loadtest"

# 기본 타겟 URL
export DEFAULT_API_URL="${DEFAULT_API_URL:-https://chat.goorm-ktb-019.goorm.team/}"
export DEFAULT_SOCKET_URL="${DEFAULT_SOCKET_URL:-https://chat.goorm-ktb-019.goorm.team/}"
