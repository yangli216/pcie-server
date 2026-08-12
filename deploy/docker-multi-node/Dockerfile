FROM eclipse-temurin:17-jre-jammy

ARG PCIE_UID=10001
ARG PCIE_GID=10001

RUN groupadd --gid "${PCIE_GID}" pcie \
    && useradd --uid "${PCIE_UID}" --gid "${PCIE_GID}" \
        --home-dir /opt/pcie-server --shell /usr/sbin/nologin pcie \
    && install --directory --owner "${PCIE_UID}" --group "${PCIE_GID}" \
        --mode 0750 /opt/pcie-server /opt/pcie-server/logs

WORKDIR /opt/pcie-server

COPY --chown=${PCIE_UID}:${PCIE_GID} \
    server/target/pcie-server-*.jar \
    /opt/pcie-server/pcie-server.jar
COPY --chown=${PCIE_UID}:${PCIE_GID} \
    deploy/docker-multi-node/container-healthcheck.sh \
    /opt/pcie-server/container-healthcheck.sh

RUN chmod 0550 /opt/pcie-server/container-healthcheck.sh

USER ${PCIE_UID}:${PCIE_GID}

EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 \
    CMD ["/opt/pcie-server/container-healthcheck.sh"]

ENTRYPOINT ["java", "-jar", "/opt/pcie-server/pcie-server.jar"]
