FROM nginxinc/nginx-unprivileged:1.30.5-alpine@sha256:0f5f7068c5f5bf7046dbf649a08810ec1693967a9f488b8ff3998be6dd34bf6c AS nginx-binary
FROM alpine:3.24@sha256:d56c381f961d307a21b3ca004cf1e3910f106644aefb1f43e654c8a56c4fd395
COPY .cache/foundation/*.apk /inputs/
RUN apk add --no-network /inputs/*.apk && rm -r /inputs && addgroup -g 101 nginx && adduser -D -H -u 101 -G nginx nginx && mkdir -p /usr/share/nginx/html
COPY --from=nginx-binary /usr/sbin/nginx /usr/sbin/nginx
LABEL org.agentplatform.rebuild="Nginx1.30.5 binary repackaged on Alpine3.24 with frozen signed security packages"
COPY --chown=101:101 frontend/dist/ /usr/share/nginx/html/
COPY --chown=101:101 deploy/nginx.conf /etc/nginx/nginx.conf
USER 101:101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
