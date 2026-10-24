FROM nginxinc/nginx-unprivileged:1.30.5-alpine@sha256:0f5f7068c5f5bf7046dbf649a08810ec1693967a9f488b8ff3998be6dd34bf6c
ARG SOURCE_REVISION=unspecified
ARG NGINX_CONFIG=deploy/nginx.conf
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
COPY --chown=101:101 frontend/dist/ /usr/share/nginx/html/
COPY --chown=101:101 ${NGINX_CONFIG} /etc/nginx/nginx.conf
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
