FROM alpine:3.17.3@sha256:124c7d2707904eea7431fffe91522a01e5a861a624ee31d03372cc1d138a3126

RUN apk update && \
    apk add openjdk17-jre-headless py3-pytest py3-requests bash && \
    rm -rf /var/cache/apk/*

WORKDIR "/rell/pytests"

CMD "/rell/pytests/run.sh"
