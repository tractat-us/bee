# Runtime image for hosted bee: bee-host at https://bee.tractat.us, on Fly app bee-tractat-us
# (fly.toml). CI builds the application distribution with `./gradlew :bee-host:installDist`, where
# the Tigris credentials that resolve us.tractat.kuilt are available, and this file only copies it.
# The image does no Gradle work, so the build context stays small and no credential reaches Fly's
# builder. Operations: docs/deploy.md.
#
# The JDK matches ci.yml's setup-java (25.0.5, Temurin).
FROM eclipse-temurin:25.0.5_8-jre-jammy

# The host runs as `bee`, never root. Fly mounts the bee_data volume at /data owned by root, so the
# entrypoint below starts as root only to hand the mount's top directory to `bee`, then drops to
# `bee` with setpriv (util-linux, in the base image) and execs the host. The chown is not
# recursive: everything under /data is written by the host, so it is already `bee`'s.
RUN groupadd --system bee \
    && useradd --system --gid bee --home-dir /app --no-create-home --shell /usr/sbin/nologin bee \
    && mkdir /data \
    && chown bee:bee /data

# bin/bee-host and lib/, including bee-host-web.jar, the page the host serves at `/`.
COPY bee-host/build/install/bee-host /app

EXPOSE 8080
ENTRYPOINT ["/bin/sh", "-c", "chown bee:bee /data && exec setpriv --reuid=bee --regid=bee --init-groups -- \"$0\" \"$@\""]
# --public-url is hosted mode (#145). In it the host refuses to start without BEE_OWNER_KEY, a Fly
# secret on the app (docs/deploy.md). JVM flags come from BEE_HOST_OPTS in fly.toml. Fly's proxy
# names each client in Fly-Client-IP; its rightmost X-Forwarded-For entry is the app's own address.
CMD ["/app/bin/bee-host", "--public-url", "https://bee.tractat.us/", "--client-ip-header", "Fly-Client-IP", "--port", "8080", "--data-dir", "/data"]
