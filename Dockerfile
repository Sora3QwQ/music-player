FROM golang:1.26.10-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
COPY web ./web
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/music-player .
FROM alpine:3.23
RUN addgroup -S -g 10001 music && adduser -S -D -u 10001 -G music music && mkdir /data && chown music:music /data
COPY --from=build /out/music-player /usr/local/bin/music-player
USER 10001:10001
ENV DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/music-player"]
