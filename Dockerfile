FROM eclipse-temurin:21-jre
WORKDIR /app
COPY server/build/install/server /app
ENTRYPOINT ["/app/bin/server"]
