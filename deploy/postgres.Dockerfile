FROM postgres:17.11-trixie@sha256:3cec7eb015ba8adb28139fa5c83b8489cdf0e666e53dfdf20f598ae0cc8739e3 AS postgres
FROM golang:1.27.2-trixie@sha256:2d658507a011f1eadc0114d7a421b312b2e77d9042a24036a26e267cc9231677 AS vector-build
COPY .cache/foundation/postgresql.tar.gz /tmp/postgresql.tar.gz
RUN mkdir /postgres-source && tar -xzf /tmp/postgresql.tar.gz -C /postgres-source --strip-components=1 && cd /postgres-source && ./configure --without-readline --without-zlib --without-icu --without-llvm && make -j2 && make install
COPY .cache/foundation/pgvector.tar.gz /tmp/pgvector.tar.gz
RUN mkdir /vector-source && tar -xzf /tmp/pgvector.tar.gz -C /vector-source --strip-components=1 && cd /vector-source && make OPTFLAGS="" with_llvm=no PG_CONFIG=/usr/local/bin/pg_config && make install with_llvm=no DESTDIR=/vector
FROM postgres
COPY --from=vector-build /vector/ /
COPY --from=vector-build /vector-source/LICENSE /usr/share/doc/pgvector/LICENSE
LABEL org.agentplatform.rebuild="PostgreSQL17.11/Trixie with pgvector0.8.1 source commit778dacf20c07caf904557a88705142631818d8cb"
