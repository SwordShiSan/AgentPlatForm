FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe AS python
FROM golang:1.27.2-trixie@sha256:2d658507a011f1eadc0114d7a421b312b2e77d9042a24036a26e267cc9231677
COPY --from=python /usr/local/ /usr/local/
COPY .cache/foundation/cross-*.deb /build-inputs/
RUN dpkg -i /build-inputs/*.deb && clang --version && test -r /usr/include/bpf/bpf_helpers.h && test -r /usr/include/bpf/bpf_endian.h && rm -r /build-inputs
COPY .cache/foundation/bazel-linux /usr/local/bin/bazel
RUN chmod 0755 /usr/local/bin/bazel && bazel --version
ENV GOTOOLCHAIN=local
WORKDIR /source
COPY .cache/foundation/gvisor-source.tar.gz /tmp/gvisor-source.tar.gz
RUN tar -xzf /tmp/gvisor-source.tar.gz --strip-components=1 -C /source
COPY deploy/security/gvisor.go.mod /source/go.mod
COPY deploy/security/gvisor.go.sum /source/go.sum
# Resolve/freeze Bazel's transitive analysis lock before test/build. These exact
# locks are exported as evidence; promotion requires reviewing the resulting lock.
RUN mkdir /out && bazel --batch mod deps && sha256sum MODULE.bazel.lock go.mod go.sum > /out/build-inputs.sha256 && cp MODULE.bazel.lock /out/
RUN bazel --batch test --lockfile_mode=error --jobs=2 --test_output=errors //pkg/buffer:buffer_test //pkg/tcpip/...
RUN bazel --batch build --lockfile_mode=error --nostamp --jobs=2 -c opt //debian:gvisor-release-tar-bz2 && cp bazel-bin/debian/gvisor.tar.bz2 /out/
RUN printf '%s\n' 'Platform security rebuild candidate, NOT accepted: source97e8e896; Bazel SDK Go1.26.9 fallback from frozen go.mod; frozen module patches; preserves full sidecar package' > /out/REBUILD.txt && sha256sum /out/gvisor.tar.bz2 > /out/runtime.sha256
