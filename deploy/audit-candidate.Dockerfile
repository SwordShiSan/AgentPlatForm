FROM golang:1.27.2-trixie@sha256:2d658507a011f1eadc0114d7a421b312b2e77d9042a24036a26e267cc9231677
ARG TOOL
ENV GOTOOLCHAIN=local CGO_ENABLED=0
COPY .cache/foundation/syft-source.tar.gz .cache/foundation/grype-source.tar.gz /inputs/
COPY deploy/security/syft.go.mod deploy/security/syft.go.sum deploy/security/grype.go.mod deploy/security/grype.go.sum /inputs/
WORKDIR /source
RUN case "$TOOL" in syft|grype) ;; *) exit 1 ;; esac && tar -xzf "/inputs/$TOOL-source.tar.gz" --strip-components=1 -C /source && cp "/inputs/$TOOL.go.mod" go.mod && cp "/inputs/$TOOL.go.sum" go.sum && go mod download && go mod verify
# Complete core/internal suites; CLI/image integration acceptance remains separate.
RUN go test -mod=readonly -p 2 -timeout 20m -tags=containers_image_openpgp,exclude_graphdriver_btrfs "./$TOOL/..." ./internal/...
RUN mkdir /out && case "$TOOL" in syft) version=1.54.1; commit=749cd89ef26db65d5102d879f5dd63a174312b1e ;; grype) version=0.120.1; commit=0d30618ef52e666f1c626f4cc463eb940cb6e498 ;; esac && go build -mod=readonly -trimpath -tags=containers_image_openpgp,exclude_graphdriver_btrfs -ldflags="-w -s -X main.version=$version+platform-r04 -X main.gitCommit=$commit -X main.gitDescription=platform-security-rebuild" -o "/out/$TOOL" "./cmd/$TOOL" && "/out/$TOOL" version > /out/version.txt && go version -m "/out/$TOOL" > /out/build-info.txt && cp go.mod go.sum LICENSE /out/ && cd /out && sha256sum "$TOOL" go.mod go.sum LICENSE > SHA256SUMS && printf '%s\n' 'Security rebuild candidate; NOT accepted; Go1.27.2 and x/net0.60.0; core/internal suites only; preserve official raw scans' > REBUILD.txt
