FROM openjdk:11-jre-slim
ENV LANG=C.UTF-8

COPY target/che-envoy-1.0-SNAPSHOT.jar /che-envoy.jar
CMD ["java", "-jar", "/che-envoy.jar"]
