# Caddy со встроенным модулем DuckDNS — нужен, чтобы получать сертификат
# через DNS-проверку (у нас нет портов 80/443 для обычной проверки).
FROM caddy:2-builder AS builder
RUN xcaddy build --with github.com/caddy-dns/duckdns

FROM caddy:2
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
