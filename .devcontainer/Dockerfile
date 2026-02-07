FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# Install Node.js 20.x (includes npm).
# We do this in the image build because devcontainer "features" are not applied
# when using a plain docker-compose service with only an "image:" reference.
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
  && mkdir -p /etc/apt/keyrings \
  && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
  && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
  && apt-get update \
  && apt-get install -y --no-install-recommends nodejs \
  && node --version \
  && npm --version \
  && apt-get clean \
  && rm -rf /var/lib/apt/lists/*

