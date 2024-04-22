FROM openjdk:17-jdk-slim
RUN apt update -y && apt install maven -y

# Set up the working directory
ENV HOME=/usr/app
RUN mkdir -p $HOME
WORKDIR $HOME

ADD pom.xml $HOME
ADD . $HOME

RUN apt-get install telnet -y


RUN ./mvnw clean install -Pdistribution -DskipTests

RUN mv quarkus/server/target/lib/quarkus-run.jar quarkus.jar

ENTRYPOINT ["java","-jar","quarkus.jar", "start"]