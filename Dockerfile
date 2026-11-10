# Builds the event kitchen board — everything under web/.
#
# Zero dependencies, so there is nothing to install and no build step: just the runtime and the
# source. Node 24 for `node:sqlite`, which is only stable from 23.
#
# On Coolify this needs no path settings at all — the Dockerfile is at the repository root, which
# is where it looks by default. Mount a volume at /data.
FROM node:24-alpine

WORKDIR /app

COPY web/package.json ./
COPY web/server ./server
COPY web/public ./public

# Created and chowned BEFORE the VOLUME is declared: changes a later layer makes to a path already
# declared as a volume are discarded, which would leave it root-owned and unwritable.
RUN mkdir -p /data && chown -R node:node /data /app

# The order book must live on a mounted volume, not in the image layer — a redeploy replaces the
# container, and an unmounted database goes with it. The app recreates an empty one, so nothing
# errors: a service would start with every outstanding queue number gone and no sign of it.
ENV DB_FILE=/data/event.db
ENV PORT=8080
VOLUME ["/data"]

EXPOSE 8080

# Coolify reads this; a container that is up but not serving should not count as healthy.
# $PORT rather than a literal, in shell form so it expands at runtime: the server honours the env
# var, and hardcoding 8080 would mark a container on any other port permanently unhealthy while it
# served perfectly.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/api/health" || exit 1

# Not root: the process only ever needs to read its own source and write one directory.
USER node

CMD ["node", "server/index.js"]
