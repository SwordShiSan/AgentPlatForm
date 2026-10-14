FROM redis:7.4.11-alpine@sha256:ca0acbb137c1dc3339c8b147a58fd6f42775d4599327b50e7b116c23de501af2 AS redis-binary
FROM alpine:3.24@sha256:d56c381f961d307a21b3ca004cf1e3910f106644aefb1f43e654c8a56c4fd395
COPY .cache/foundation/*.apk /inputs/
RUN apk add --no-network /inputs/*.apk && rm -r /inputs && mkdir /data && chown 999:999 /data
COPY --from=redis-binary /usr/local/bin/redis-* /usr/local/bin/
COPY --from=redis-binary /usr/local/bin/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY deploy/security/redis-LICENSE.txt /usr/share/licenses/redis/LICENSE.txt
LABEL org.agentplatform.rebuild="Redis7.4.11 binary repackaged on Alpine3.24; RSALv2 OR SSPL-1.0"
USER 999:999
WORKDIR /data
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["redis-server"]
