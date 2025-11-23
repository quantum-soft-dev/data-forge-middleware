# Stage 1: Build React application with Vite
FROM node:24-alpine AS builder

# Set working directory
WORKDIR /app

# Copy package files
COPY package*.json ./

# Install all dependencies (needed for build tools like tsc and vite)
RUN npm ci --ignore-scripts

# Copy all frontend source code
COPY . .

# Build the application
RUN npm run build

# Stage 2: Serve with NGINX
FROM nginx:1.25-alpine AS production

# Install gettext for envsubst (environment variable substitution)
RUN apk add --no-cache gettext

# Copy built files from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy NGINX configuration template
COPY nginx.conf.template /etc/nginx/templates/nginx.conf.template

# Copy environment configuration template
COPY public/env-config.js.template /usr/share/nginx/html/env-config.js.template

# Create startup script for runtime environment variable injection
RUN echo '#!/bin/sh' > /docker-entrypoint.sh && \
    echo 'set -e' >> /docker-entrypoint.sh && \
    echo '' >> /docker-entrypoint.sh && \
    echo '# Set default values for environment variables' >> /docker-entrypoint.sh && \
    echo 'export BACKEND_URL=${BACKEND_URL:-http://backend:8080}' >> /docker-entrypoint.sh && \
    echo '' >> /docker-entrypoint.sh && \
    echo '# Substitute environment variables in NGINX config template' >> /docker-entrypoint.sh && \
    echo 'envsubst '\''$BACKEND_URL'\'' < /etc/nginx/templates/nginx.conf.template > /etc/nginx/conf.d/default.conf' >> /docker-entrypoint.sh && \
    echo '' >> /docker-entrypoint.sh && \
    echo '# Substitute environment variables in env-config.js template' >> /docker-entrypoint.sh && \
    echo 'envsubst < /usr/share/nginx/html/env-config.js.template > /usr/share/nginx/html/env-config.js' >> /docker-entrypoint.sh && \
    echo '' >> /docker-entrypoint.sh && \
    echo '# Start NGINX' >> /docker-entrypoint.sh && \
    echo 'exec nginx -g "daemon off;"' >> /docker-entrypoint.sh && \
    chmod +x /docker-entrypoint.sh

# Expose port 80
EXPOSE 80

# Health check endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/health || exit 1

# Use custom entrypoint for env injection
ENTRYPOINT ["/docker-entrypoint.sh"]
