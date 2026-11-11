FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY contracts ./contracts
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/fos-integration-0.2.0.war app.war
USER 10001:10001
EXPOSE 8443 8444
ENTRYPOINT ["java","-jar","/app/app.war"]
