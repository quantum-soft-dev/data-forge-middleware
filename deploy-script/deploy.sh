#!/bin/bash

set -e

# =============================================================================
# DFM CloudFormation Deployment Script
# =============================================================================
# Usage:
#   ./deploy.sh <env> <command>
# Examples:
#   ./deploy.sh dev create         # Create new dev stack
#   ./deploy.sh dev update         # Update dev stack
#   ./deploy.sh prod status        # Check prod stack status
# =============================================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# -----------------------------------------------------------------------------
# Helper functions
# -----------------------------------------------------------------------------

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

get_stack_status() {
    aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" \
        --query 'Stacks[0].StackStatus' \
        --output text 2>/dev/null || echo "DOES_NOT_EXIST"
}

stack_exists() {
    local status=$(get_stack_status)
    [[ "$status" != "DOES_NOT_EXIST" ]]
}

wait_for_stack() {
    local operation=$1
    local wait_command="stack-${operation}-complete"

    log_info "Waiting for stack $operation to complete..."

    if aws cloudformation wait "$wait_command" \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" 2>/dev/null; then
        log_success "Stack $operation completed successfully"
        return 0
    else
        log_error "Stack $operation failed"
        return 1
    fi
}

# -----------------------------------------------------------------------------
# Configuration & Setup
# -----------------------------------------------------------------------------

ENV=${1:-dev}
COMMAND=${2:-status}
TEMPLATE_FILE="template-1763397226530.yaml"
PARAMETERS_FILE="config/${ENV}.json"
STACK_NAME="dfm-${ENV}"
AWS_REGION="us-east-1"

# If parameters file doesn't exist but template does, generate it
if [[ ! -f "$PARAMETERS_FILE" && -f "${PARAMETERS_FILE}.template" ]]; then
    log_info "Generating $PARAMETERS_FILE from template..."
    # Check if envsubst is available
    if command -v envsubst &> /dev/null; then
        envsubst < "${PARAMETERS_FILE}.template" > "$PARAMETERS_FILE"
        log_success "Generated configuration for $ENV"
    else
        log_error "envsubst not found. Please install gettext."
        exit 1
    fi
fi

# -----------------------------------------------------------------------------
# Stack operations
# -----------------------------------------------------------------------------

validate_template() {
    log_info "Validating template..."

    if aws cloudformation validate-template \
        --template-body "file://$TEMPLATE_FILE" \
        --region "$AWS_REGION" > /dev/null; then
        log_success "Template is valid"
        return 0
    else
        log_error "Template validation failed"
        return 1
    fi
}

create_stack() {
    log_info "Creating stack: $STACK_NAME"

    if stack_exists; then
        local status=$(get_stack_status)
        log_error "Stack already exists with status: $status"
        log_info "Use './deploy.sh $ENV update' to update or './deploy.sh $ENV delete' to delete first"
        return 1
    fi

    validate_template || return 1

    aws cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE" \
        --parameters "file://$PARAMETERS_FILE" \
        --capabilities CAPABILITY_NAMED_IAM \
        --region "$AWS_REGION" \
        --tags Key=Environment,Value=$ENV Key=Project,Value=dfm

    wait_for_stack "create"

    if [ $? -eq 0 ]; then
        show_outputs
    fi
}

update_stack() {
    log_info "Updating stack: $STACK_NAME via Change Set"

    if ! stack_exists; then
        log_error "Stack does not exist. Use './deploy.sh $ENV create' first"
        return 1
    fi

    local status=$(get_stack_status)
    if [[ "$status" == *"IN_PROGRESS"* ]]; then
        log_error "Stack is currently in progress: $status"
        log_info "Wait for current operation to complete"
        return 1
    fi

    validate_template || return 1

    # Generate unique change set name
    local change_set_name="update-$(date +%Y%m%d-%H%M%S)"

    log_info "Creating change set: $change_set_name"

    aws cloudformation create-change-set \
        --stack-name "$STACK_NAME" \
        --change-set-name "$change_set_name" \
        --template-body "file://$TEMPLATE_FILE" \
        --parameters "file://$PARAMETERS_FILE" \
        --capabilities CAPABILITY_NAMED_IAM \
        --region "$AWS_REGION" \
        --description "Update from deploy.sh at $(date)"

    log_info "Waiting for change set to be created..."

    aws cloudformation wait change-set-create-complete \
        --stack-name "$STACK_NAME" \
        --change-set-name "$change_set_name" \
        --region "$AWS_REGION" 2>/dev/null

    # Check if change set has changes
    local change_set_status=$(aws cloudformation describe-change-set \
        --stack-name "$STACK_NAME" \
        --change-set-name "$change_set_name" \
        --region "$AWS_REGION" \
        --query 'Status' \
        --output text)

    local execution_status=$(aws cloudformation describe-change-set \
        --stack-name "$STACK_NAME" \
        --change-set-name "$change_set_name" \
        --region "$AWS_REGION" \
        --query 'ExecutionStatus' \
        --output text)

    if [[ "$change_set_status" == "FAILED" ]]; then
        local reason=$(aws cloudformation describe-change-set \
            --stack-name "$STACK_NAME" \
            --change-set-name "$change_set_name" \
            --region "$AWS_REGION" \
            --query 'StatusReason' \
            --output text)

        if [[ "$reason" == *"didn't contain changes"* ]]; then
            log_warn "No changes detected. Stack is up to date."
            aws cloudformation delete-change-set \
                --stack-name "$STACK_NAME" \
                --change-set-name "$change_set_name" \
                --region "$AWS_REGION"
            return 0
        else
            log_error "Change set failed: $reason"
            return 1
        fi
    fi

    # Show changes
    echo ""
    log_info "Changes to be applied:"
    echo "─────────────────────────────────────────────────────────────────────"
    aws cloudformation describe-change-set \
        --stack-name "$STACK_NAME" \
        --change-set-name "$change_set_name" \
        --region "$AWS_REGION" \
        --query 'Changes[*].[ResourceChange.Action, ResourceChange.LogicalResourceId, ResourceChange.ResourceType]' \
        --output table
    echo "─────────────────────────────────────────────────────────────────────"
    echo ""

    log_info "Executing change set..."

    aws cloudformation execute-change-set \
        --stack-name "$STACK_NAME" \
        --change-set-name "$change_set_name" \
        --region "$AWS_REGION"

    wait_for_stack "update"

    if [ $? -eq 0 ]; then
        show_outputs
    fi
}

delete_stack() {
    log_warn "Deleting stack: $STACK_NAME"

    aws cloudformation delete-stack \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION"

    wait_for_stack "delete"
}

show_status() {
    if ! stack_exists; then
        log_info "Stack does not exist"
        return 0
    fi

    echo ""
    echo "Stack: $STACK_NAME"
    echo "─────────────────────────────────────────────────────────────────────"

    aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" \
        --query 'Stacks[0].{Status:StackStatus,Created:CreationTime,Updated:LastUpdatedTime,Description:Description}' \
        --output table

    echo ""
    log_info "Resource Status:"
    aws cloudformation describe-stack-resources \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" \
        --query 'StackResources[*].{Resource:LogicalResourceId,Type:ResourceType,Status:ResourceStatus}' \
        --output table
}

show_outputs() {
    if ! stack_exists; then
        log_error "Stack does not exist"
        return 1
    fi

    echo ""
    log_info "Stack Outputs:"
    echo "─────────────────────────────────────────────────────────────────────"

    aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" \
        --query 'Stacks[0].Outputs[*].{Key:OutputKey,Value:OutputValue}' \
        --output table
}

show_events() {
    if ! stack_exists; then
        log_error "Stack does not exist"
        return 1
    fi

    log_info "Recent Stack Events (last 20):"
    echo "─────────────────────────────────────────────────────────────────────"

    aws cloudformation describe-stack-events \
        --stack-name "$STACK_NAME" \
        --region "$AWS_REGION" \
        --query 'StackEvents[0:20].[Timestamp,LogicalResourceId,ResourceStatus,ResourceStatusReason]' \
        --output table
}

show_logs() {
    local minutes=${1:-10}

    log_info "ECS Logs (last $minutes minutes):"
    echo "─────────────────────────────────────────────────────────────────────"

    aws logs tail "/ecs/$STACK_NAME/dfm" \
        --since "${minutes}m" \
        --region "$AWS_REGION" 2>/dev/null || \
        log_warn "Log group not found or no logs available"
}

rollback_stack() {
    local status=$(get_stack_status)

    if [[ "$status" == "UPDATE_IN_PROGRESS" ]]; then
        log_info "Cancelling update and rolling back..."

        aws cloudformation cancel-update-stack \
            --stack-name "$STACK_NAME" \
            --region "$AWS_REGION"

        log_info "Waiting for rollback to complete..."
        aws cloudformation wait stack-rollback-complete \
            --stack-name "$STACK_NAME" \
            --region "$AWS_REGION"

        log_success "Rollback completed"
    else
        log_error "Stack is not in UPDATE_IN_PROGRESS state. Current: $status"
    fi
}

force_deploy() {
    log_info "Force deploying ECS service (new task deployment)..."

    aws ecs update-service \
        --cluster "${STACK_NAME}-cluster" \
        --service "${STACK_NAME}-dfm" \
        --force-new-deployment \
        --region "$AWS_REGION" \
        --query 'service.serviceName' \
        --output text

    log_success "New deployment initiated"
}

show_help() {
    echo "Usage: $0 <env> <command>"
    echo ""
    echo "Commands:"
    echo "  create       Create new stack"
    echo "  update       Update stack via Change Set"
    echo "  delete       Delete stack"
    echo "  deploy       Force new ECS deployment (pull latest image)"
    echo "  status       Show stack status and resources"
    echo "  outputs      Show stack outputs"
    echo "  events       Show recent stack events"
    echo "  logs [min]   Show ECS logs (default: 10 minutes)"
    echo "  rollback     Cancel in-progress update and rollback"
    echo "  help         Show this help"
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

# Check dependencies
if ! command -v aws &> /dev/null; then
    log_error "AWS CLI not found. Please install it first."
    exit 1
fi

# Check files exist
if [[ ! -f "$TEMPLATE_FILE" ]]; then
    log_error "Template file not found: $TEMPLATE_FILE"
    exit 1
fi

if [[ ! -f "$PARAMETERS_FILE" ]]; then
    log_error "Parameters file not found: $PARAMETERS_FILE"
    exit 1
fi

# Parse command
case "$COMMAND" in
    create)
        create_stack
        ;;
    update)
        update_stack
        ;;
    delete)
        delete_stack
        ;;
    status)
        show_status
        ;;
    outputs)
        show_outputs
        ;;
    events)
        show_events
        ;;
    logs)
        show_logs "${3:-10}"
        ;;
    rollback)
        rollback_stack
        ;;
    deploy|force)
        force_deploy
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        log_error "Unknown command: $COMMAND"
        show_help
        exit 1
        ;;
esac