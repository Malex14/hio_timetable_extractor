FROM gradle:8-jdk21 AS builder

COPY . /build
WORKDIR /build
RUN gradle jar

FROM eclipse-temurin:21-jre-alpine

COPY --from=builder /build/build/libs/hio_timetable_extractor.jar /run.jar
RUN apk add git openssh
COPY ./git /root

CMD ["java", "-jar", "/run.jar"]