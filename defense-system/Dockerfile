FROM node:22.23.3-alpine3.23 AS build-stage

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci
COPY . .

ARG VUE_APP_BASE_API=/api
ARG VUE_APP_BASE_TEXT_WS=/ws/defense-assessment
ARG VUE_APP_BASE_VOICE_WS=/ws/voice/defense-assessment
ARG VUE_APP_BASE_PLAYVOICE_WS=/ws/voice-play/defense-assessment
ENV VUE_APP_BASE_API=${VUE_APP_BASE_API}
ENV VUE_APP_BASE_TEXT_WS=${VUE_APP_BASE_TEXT_WS}
ENV VUE_APP_BASE_VOICE_WS=${VUE_APP_BASE_VOICE_WS}
ENV VUE_APP_BASE_PLAYVOICE_WS=${VUE_APP_BASE_PLAYVOICE_WS}
RUN npm run build

FROM nginx:1.30.5-alpine3.24 AS production-stage

COPY --from=build-stage --chown=nginx:nginx /app/dist /usr/share/nginx/html
COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf
RUN sed -i 's!^pid .*;!pid /tmp/nginx.pid;!' /etc/nginx/nginx.conf \
  && chown -R nginx:nginx /var/cache/nginx /usr/share/nginx/html

USER nginx
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1

ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
