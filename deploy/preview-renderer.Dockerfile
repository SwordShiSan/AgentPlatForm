FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:7fd597bf48c8bb13a7a3fb227f8366dec0423f78775d4b4f7e30409204af8627
LABEL org.opencontainers.image.title="AgentPlatform isolated preview renderer"
ARG LIBREOFFICE_VERSION=26.2.6
ARG LIBREOFFICE_SHA256=fd0e8f8f2408dd2e5b90286e60f3f97cf566ba441cd48cfc5bcc68067303e0bc
RUN sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.ustc.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.ustc.edu.cn/ubuntu|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl tini libxinerama1 libxrender1 libxext6 libsm6 libice6 libgl1 libdbus-1-3 libfontconfig1 \
      libcups2 libnss3 libnspr4 libxcb1 libxslt1.1 libxml2 libexpat1 libx11-6 libfreetype6 \
      fonts-noto-cjk fonts-liberation fonts-crosextra-carlito fonts-crosextra-caladea \
    && rm -rf /var/lib/apt/lists/*
COPY .cache/foundation/LibreOffice_26.2.6_Linux_x86-64_deb.tar.gz /tmp/libreoffice.tar.gz
RUN echo "${LIBREOFFICE_SHA256}  /tmp/libreoffice.tar.gz" | sha256sum --check --strict \
    && mkdir /tmp/lo && tar -xzf /tmp/libreoffice.tar.gz --directory /tmp/lo --strip-components=1 \
    && apt-get update && apt-get install -y --no-install-recommends /tmp/lo/DEBS/*.deb \
    && dpkg-query -W > /opt/renderer-packages.txt \
    && rm -rf /tmp/lo /tmp/libreoffice.tar.gz /var/lib/apt/lists/* \
    && mkdir -p /opt/platform/lib /input && chown 10001:10001 /input
RUN apt-get update && apt-get install -y --no-install-recommends libcairo2 libglib2.0-0t64 \
    && /opt/libreoffice26.2/program/soffice.bin --headless --version \
    && dpkg-query -W > /opt/renderer-packages.txt \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/platform
COPY backend/platform-app/target/platform-app-0.1.0-SNAPSHOT-renderer.jar /opt/platform/renderer.jar
COPY backend/platform-app/target/renderer-libs/ /opt/platform/lib/
COPY deploy/preview/hardened-profile/ /opt/platform/hardened-profile/
ENV HOME=/tmp/preview JAVA_TOOL_OPTIONS="" SAL_USE_VCLPLUGIN=svp
USER 10001:10001
ARG SOURCE_REVISION=unspecified
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
ENTRYPOINT ["/usr/bin/tini","--","java","-Xms16m","-Xmx192m","-XX:MaxDirectMemorySize=16m","-Djava.awt.headless=true","-Djava.io.tmpdir=/tmp/preview","-cp","/opt/platform/renderer.jar:/opt/platform/lib/*","com.agentplatform.renderer.PreviewRendererMain"]
