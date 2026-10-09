FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/nacos-mcp-server-0.1.0.jar app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
