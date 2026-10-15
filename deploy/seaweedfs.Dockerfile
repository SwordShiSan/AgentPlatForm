FROM golang:1.27.2-trixie@sha256:2d658507a011f1eadc0114d7a421b312b2e77d9042a24036a26e267cc9231677 AS weed-build
ENV GOTOOLCHAIN=local CGO_ENABLED=0 GOSUMDB=sum.golang.org
WORKDIR /source
COPY .cache/foundation/seaweedfs.tar.gz /tmp/seaweedfs.tar.gz
RUN tar -xzf /tmp/seaweedfs.tar.gz --strip-components=1 -C /source
COPY deploy/security/seaweedfs.go.mod /source/go.mod
COPY deploy/security/seaweedfs.go.sum /source/go.sum
RUN go mod download && go mod verify
RUN go test -mod=readonly -p 2 -timeout 10m ./weed/...
RUN go build -mod=readonly -trimpath -ldflags="-X github.com/seaweedfs/seaweedfs/weed/util/version.COMMIT=530be3e-security-xnet060" -o /out/weed ./weed
FROM alpine:3.24@sha256:d56c381f961d307a21b3ca004cf1e3910f106644aefb1f43e654c8a56c4fd395
COPY .cache/foundation/*.apk /inputs/
RUN apk add --no-network /inputs/*.apk && rm -r /inputs && mkdir -p /etc/seaweedfs /data && chown 10001:10001 /data
COPY --from=weed-build /out/weed /usr/bin/weed
COPY --from=weed-build /source/docker/filer.toml /etc/seaweedfs/filer.toml
COPY --from=weed-build /source/LICENSE /usr/share/licenses/seaweedfs/LICENSE
COPY --from=weed-build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
LABEL org.agentplatform.rebuild="SeaweedFS4.48 source530be3e; Go1.27.2; x/net0.60.0; upstream storage format retained"
ENV GODEBUG=fips140=on
USER 10001:10001
WORKDIR /data
ENTRYPOINT ["weed"]
CMD ["version"]
