FROM nginxinc/nginx-unprivileged:1.30.5-alpine@sha256:0f5f7068c5f5bf7046dbf649a08810ec1693967a9f488b8ff3998be6dd34bf6c
COPY --chown=101:101 frontend/dist/ /usr/share/nginx/html/
COPY --chown=101:101 deploy/nginx.conf /etc/nginx/nginx.conf
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
