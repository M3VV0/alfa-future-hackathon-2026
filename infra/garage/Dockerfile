FROM dxflrs/garage:v2.1.0 AS garage
FROM alpine:3.21.3
COPY --from=garage /garage /usr/local/bin/garage
COPY init.sh /init.sh
ENTRYPOINT ["/bin/sh","/init.sh"]
