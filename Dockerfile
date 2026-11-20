FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd -r geogroup && useradd -r -g geogroup geouser
COPY target/api-geo-0.0.1-SNAPSHOT.jar app.jar
RUN chown -R geouser:geogroup /app
USER geouser
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
