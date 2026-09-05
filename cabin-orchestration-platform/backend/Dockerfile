# Stage 1: build — uses official Maven image (includes mvn + JDK 21)
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime — slim JRE + Bitwarden CLI (VaultwardenOAuthCredentialStore
# shells out to `bw` -- see that class's own javadoc for why a subprocess,
# not a raw REST client, is the correct approach here). Installed via npm,
# not Bitwarden's own standalone binary release -- that binary is glibc-only
# and does not run on Alpine's musl libc (confirmed against real community
# reports of relocation errors, e.g. fcntl64/gnu_get_libc_version, from
# people who tried it directly). The npm package is pure JS on top of
# Node.js, which Alpine's own package repo ships a musl-native build of, so
# this avoids the incompatibility entirely instead of working around it.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache nodejs npm && npm install -g @bitwarden/cli
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
