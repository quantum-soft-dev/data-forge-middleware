#!/bin/bash
# Development environment management script for Data Forge Middleware
# Manages infrastructure services only (PostgreSQL, Keycloak, LocalStack)
# Run dfm-backend from IntelliJ IDEA for debugging

set -e

COMPOSE_FILE="docker-compose.dev.yml"
S3_BUCKET="dfm-uploads"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

check_docker() {
    if ! docker info > /dev/null 2>&1; then
        print_error "Docker is not running. Please start Docker and try again."
        exit 1
    fi
}

check_docker_compose() {
    if ! command -v docker-compose &> /dev/null; then
        print_error "docker-compose is not installed. Please install it and try again."
        exit 1
    fi
}

start_services() {
    print_header "Starting Development Environment"

    check_docker
    check_docker_compose

    print_info "Starting infrastructure services (PostgreSQL, Keycloak, LocalStack)..."
    docker-compose -f "$COMPOSE_FILE" up -d

    echo ""
    print_info "Waiting for services to start..."
    sleep 10

    # Check PostgreSQL health
    if docker-compose -f "$COMPOSE_FILE" ps | grep -q "postgres.*healthy"; then
        print_success "PostgreSQL is healthy"
    else
        print_warning "PostgreSQL may not be ready yet"
    fi

    # Check Keycloak is running
    if docker-compose -f "$COMPOSE_FILE" ps | grep -q "keycloak.*Up"; then
        print_success "Keycloak is running"
    else
        print_warning "Keycloak may not be ready yet"
    fi

    # Check LocalStack is running
    if docker-compose -f "$COMPOSE_FILE" ps | grep -q "localstack.*healthy"; then
        print_success "LocalStack is healthy"
    else
        print_warning "LocalStack may not be ready yet"
    fi

    # Create S3 bucket
    echo ""
    print_info "Creating S3 bucket in LocalStack..."
    sleep 5

    if aws --endpoint-url=http://localhost:4566 s3 ls s3://$S3_BUCKET 2>/dev/null; then
        print_info "S3 bucket '$S3_BUCKET' already exists"
    else
        if aws --endpoint-url=http://localhost:4566 s3 mb s3://$S3_BUCKET 2>/dev/null; then
            print_success "S3 bucket '$S3_BUCKET' created"
        else
            print_warning "Failed to create S3 bucket. You may need to create it manually."
        fi
    fi

    echo ""
    print_success "Development environment is ready!"
    echo ""
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}📍 Service URLs:${NC}"
    echo -e "${GREEN}   - PostgreSQL:     localhost:5432${NC}"
    echo -e "${GREEN}     • Database:     dfm${NC}"
    echo -e "${GREEN}     • User:         dfm${NC}"
    echo -e "${GREEN}     • Password:     dfm_password${NC}"
    echo -e "${GREEN}   - Keycloak Admin: http://localhost:8081${NC}"
    echo -e "${GREEN}     • Admin:        admin / admin${NC}"
    echo -e "${GREEN}   - LocalStack S3:  http://localhost:4566${NC}"
    echo -e "${GREEN}     • Bucket:       $S3_BUCKET${NC}"
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    echo -e "${BLUE}👨‍💻 Next steps:${NC}"
    echo -e "${BLUE}   1. Open project in IntelliJ IDEA${NC}"
    echo -e "${BLUE}   2. Edit Run Configuration:${NC}"
    echo -e "${BLUE}      - Active profiles: dev${NC}"
    echo -e "${BLUE}      - Or VM options: -Dspring.profiles.active=dev${NC}"
    echo -e "${BLUE}   3. Run/Debug application from IDE${NC}"
    echo ""
    echo -e "${BLUE}📝 Useful commands:${NC}"
    echo -e "${BLUE}   - View logs:    docker-compose -f $COMPOSE_FILE logs -f${NC}"
    echo -e "${BLUE}   - Stop:         ./scripts/docker-dev.sh stop${NC}"
    echo -e "${BLUE}   - Restart:      ./scripts/docker-dev.sh restart${NC}"
    echo -e "${BLUE}   - Clean:        ./scripts/docker-dev.sh clean${NC}"
    echo ""
}

stop_services() {
    print_header "Stopping Development Environment"

    check_docker_compose

    print_info "Stopping services..."
    docker-compose -f "$COMPOSE_FILE" stop

    print_success "Services stopped"
    print_info "Data volumes are preserved. Use 'start' to resume or 'clean' to remove all data."
}

restart_services() {
    print_header "Restarting Development Environment"

    check_docker_compose

    print_info "Restarting services..."
    docker-compose -f "$COMPOSE_FILE" restart

    print_success "Services restarted"
}

status_services() {
    print_header "Development Environment Status"

    check_docker_compose

    echo ""
    docker-compose -f "$COMPOSE_FILE" ps
    echo ""
}

logs_services() {
    check_docker_compose

    if [ -n "$2" ]; then
        print_info "Showing logs for: $2"
        docker-compose -f "$COMPOSE_FILE" logs -f "$2"
    else
        print_info "Showing logs for all services (press Ctrl+C to exit)"
        docker-compose -f "$COMPOSE_FILE" logs -f
    fi
}

clean_environment() {
    print_header "Cleaning Development Environment"

    check_docker_compose

    print_warning "This will remove all containers, volumes, and data!"
    read -p "Are you sure? (yes/no): " confirm

    if [ "$confirm" != "yes" ]; then
        print_info "Cleanup cancelled"
        exit 0
    fi

    print_info "Stopping and removing containers..."
    docker-compose -f "$COMPOSE_FILE" down -v

    print_success "Development environment cleaned"
    print_info "All data has been removed. Use 'start' to create a fresh environment."
}

show_usage() {
    echo ""
    echo "Usage: $0 {start|stop|restart|status|logs|clean}"
    echo ""
    echo "Commands:"
    echo "  start    - Start all infrastructure services (PostgreSQL, Keycloak, LocalStack)"
    echo "  stop     - Stop all services (preserves data)"
    echo "  restart  - Restart all services"
    echo "  status   - Show status of all services"
    echo "  logs     - Show logs (add service name to filter: logs postgres)"
    echo "  clean    - Stop and remove all containers and volumes (removes all data)"
    echo ""
    echo "Examples:"
    echo "  $0 start              # Start development environment"
    echo "  $0 logs postgres      # View PostgreSQL logs"
    echo "  $0 stop               # Stop services"
    echo "  $0 clean              # Clean everything"
    echo ""
}

# Main script logic
case "${1:-}" in
    start)
        start_services
        ;;
    stop)
        stop_services
        ;;
    restart)
        restart_services
        ;;
    status)
        status_services
        ;;
    logs)
        logs_services "$@"
        ;;
    clean)
        clean_environment
        ;;
    *)
        show_usage
        exit 1
        ;;
esac
