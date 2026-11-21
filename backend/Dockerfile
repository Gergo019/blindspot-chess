# Build stage
# Build stage. JDK 25 (LTS), the version the code targets; pinned by digest so the build
# image only changes through an explicit Dependabot PR. Tag changes are ignored there (see
# .github/dependabot.yml), because the JDK hides in the tag suffix.
FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
WORKDIR /build
COPY pom.xml .
# One retry, with -U to get past Maven's cached "not found": Maven Central has briefly answered "not
# found" for existing artifacts (#70).
RUN mvn -B -q dependency:go-offline \
    || (echo "Dependency resolution failed; retrying once in 30 s with -U (#70)" \
        && sleep 30 && mvn -B -q -U dependency:go-offline)
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:25-jre@sha256:945247f9d092703e0c6be92243ef3c51640229e23ab34d353c8dcdc73e6cd4e6
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
