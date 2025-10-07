#!/bin/bash
# Quick start script for Data Forge Middleware with Docker Compose

set -e

echo "🚀 Starting Data Forge Middleware..."
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null; then
    echo "❌ Error: docker-compose is not installed. Please install it and try again."
    exit 1
fi

echo "✅ Docker is running"
echo ""

# Start services
echo "📦 Starting services (PostgreSQL, Keycloak, LocalStack, DFM Backend)..."
docker-compose up -d

echo ""
echo "⏳ Waiting for services to be healthy..."
echo ""

# Wait for services
max_attempts=60
attempt=0

while [ $attempt -lt $max_attempts ]; do
    if docker-compose ps | grep -q "Up (healthy)"; then
        healthy_count=$(docker-compose ps | grep -c "Up (healthy)" || true)
        total_count=$(docker-compose ps | grep -c "Up" || true)

        echo "   Healthy services: $healthy_count/$total_count"

        if [ "$healthy_count" -eq "$total_count" ]; then
            echo ""
            echo "✅ All services are healthy!"
            break
        fi
    fi

    attempt=$((attempt + 1))
    sleep 5
done

if [ $attempt -eq $max_attempts ]; then
    echo ""
    echo "⚠️  Warning: Services took longer than expected to start."
    echo "   Check logs with: docker-compose logs"
fi

echo ""
echo "🎉 Data Forge Middleware is ready!"
echo ""
echo "📍 Service URLs:"
echo "   - Application API: http://localhost:8080"
echo "   - Swagger UI:      http://localhost:8080/swagger-ui.html"
echo "   - Keycloak Admin:  http://localhost:8081 (admin/admin)"
echo "   - PostgreSQL:      localhost:5432"
echo "   - LocalStack S3:   http://localhost:4566"
echo ""
echo "👥 Pre-configured users:"
echo "   - Admin: admin / admin (ROLE_ADMIN)"
echo "   - User:  user / user (ROLE_USER)"
echo ""
echo "📝 Useful commands:"
echo "   - View logs:       docker-compose logs -f"
echo "   - Stop services:   docker-compose down"
echo "   - Restart:         docker-compose restart"
echo "   - Clean start:     docker-compose down -v && docker-compose up -d"
echo ""
