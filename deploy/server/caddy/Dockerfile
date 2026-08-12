# Caddy со встроенным модулем DuckDNS — нужен, чтобы получать сертификат
# через DNS-проверку (не требует входящих портов 80/443).
#
# ВЕРСИИ ЗАКРЕПЛЕНЫ НАМЕРЕННО, не убирать. Плавающий `caddy:2-builder` тянет
# последний Caddy (2.11.x), который перешёл на библиотеку libdns v1.1,
# а плагин caddy-dns/duckdns (последняя версия v0.5.0) собран под libdns v1.0.0-beta.1
# со старым интерфейсом. Сборка падает с `xcaddy [FATAL] exit status 1`.
# Caddy 2.10.0 — последняя версия на той же libdns, что и плагин.
FROM caddy:2.10-builder AS builder
RUN xcaddy build v2.10.0 --with github.com/caddy-dns/duckdns@v0.5.0

FROM caddy:2.10
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
