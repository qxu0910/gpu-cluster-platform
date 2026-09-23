FROM busybox:1.37.0
ARG APP_VERSION=v1
RUN mkdir -p /www && echo "$APP_VERSION" > /www/index.html && echo ready > /www/ready
USER 10001:10001
EXPOSE 8080
CMD ["httpd", "-f", "-p", "8080", "-h", "/www"]
