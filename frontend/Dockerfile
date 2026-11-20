# Frontend image (#50): build with Node, serve the static files with nginx as a non-root user.
# Both base images are pinned by digest (multi-platform index, so the same pin builds on amd64 CI
# and arm64 Macs); Dependabot proposes digest updates as explicit PRs.

# ---- Build ----
# Node 26 is the Active LTS (since 2026-10-28); keep in step with .nvmrc and package.json "engines".
FROM node:26.13.1-bookworm-slim@sha256:5df79989ed685e09f0a98019db4cebfb22fb9df1a53c0161664a011f7ff6b0f1 AS build
WORKDIR /app
COPY package.json package-lock.json .nvmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---- Runtime ----
# nginx-unprivileged runs as user nginx (uid 101) and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e AS runtime
ENV BACKEND_ORIGIN=http://backend:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
