#!/bin/bash
# Production deployment script for Data Forge Middleware

set -e

echo "🚀 Data Forge Middleware - Production Deployment"
echo ""

# Check for .env file
if [ ! -f .env ]; then
    echo "❌ Error: .env file not found!"
    echo "   Please create .env file from .env.example:"
    echo "   cp .env.example .env"
    echo "   Then update with production values."
    exit 1
fi

# Validate required environment variables
required_vars=(
    "POSTGRES_PASSWORD"
    "DFM_DB_PASSWORD"
    "KEYCLOAK_DB_PASSWORD"
    "KEYCLOAK_ADMIN_PASSWORD"
    "JWT_SECRET"
    "AWS_ACCESS_KEY_ID"
    "AWS_SECRET_ACCESS_KEY"
)

echo "🔍 Validating environment variables..."
missing_vars=()

for var in "${required_vars[@]}"; do
    if ! grep -q "^${var}=" .env || grep -q "^${var}=$" .env || grep -q "^${var}=.*changeme.*" .env || grep -q "^${var}=.*your_.*" .env; then
        missing_vars+=("$var")
    fi
done

if [ ${#missing_vars[@]} -ne 0 ]; then
    echo "❌ Error: Missing or invalid environment variables:"
    for var in "${missing_vars[@]}"; do
        echo "   - $var"
    done
    echo ""
    echo "   Please update .env file with production values."
    exit 1
fi

echo "✅ Environment variables validated"
echo ""

# Check JWT secret length
jwt_secret=$(grep "^JWT_SECRET=" .env | cut -d'=' -f2-)
if [ ${#jwt_secret} -lt 32 ]; then
    echo "❌ Error: JWT_SECRET must be at least 32 characters (256 bits)"
    exit 1
fi

echo "✅ JWT secret length validated"
echo ""

# Build Docker image
echo "🏗️  Building Docker image..."
docker build --target production -t dfm-backend:latest .

if [ $? -ne 0 ]; then
    echo "❌ Error: Docker build failed"
    exit 1
fi

echo "✅ Docker image built successfully"
echo ""

# Start production services
echo "📦 Starting production services..."
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d

echo ""
echo "⏳ Waiting for services to start..."
sleep 10

# Check health
echo ""
echo "🏥 Checking service health..."

max_attempts=30
attempt=0

while [ $attempt -lt $max_attempts ]; do
    if curl -f -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "✅ Application is healthy"
        break
    fi

    attempt=$((attempt + 1))
    echo "   Attempt $attempt/$max_attempts..."
    sleep 5
done

if [ $attempt -eq $max_attempts ]; then
    echo "❌ Error: Application failed to start properly"
    echo "   Check logs with: docker-compose logs dfm-backend"
    exit 1
fi

echo ""
echo "🎉 Deployment successful!"
echo ""
echo "📍 Production services:"
echo "   - Application: http://localhost:8080"
echo "   - Health:      http://localhost:8080/actuator/health"
echo "   - Metrics:     http://localhost:8080/actuator/metrics"
echo ""
echo "📝 Management commands:"
echo "   - View logs:   docker-compose logs -f dfm-backend"
echo "   - Stop:        docker-compose -f docker-compose.yml -f docker-compose.prod.yml down"
echo "   - Restart:     docker-compose -f docker-compose.yml -f docker-compose.prod.yml restart"
echo ""
echo "⚠️  Production checklist:"
echo "   1. Set up SSL/TLS certificates"
echo "   2. Configure firewall rules"
echo "   3. Set up log aggregation"
echo "   4. Configure monitoring and alerts"
echo "   5. Set up automated backups"
echo "   6. Review and update CORS settings"
echo ""
