#!/usr/bin/env sh
set -eu

# 敏感配置只写入被 Git 忽略的本地文件，已有文件不覆盖。
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  printf '%s\n' '.env 已存在，未做修改。'
  exit 0
fi
if ! command -v openssl >/dev/null 2>&1; then
  printf '%s\n' '需要 openssl 生成随机密钥。' >&2
  exit 1
fi
umask 077
database_password="$(openssl rand -hex 24)"
jwt_secret="$(openssl rand -hex 48)"
# 使用排他创建，避免并发执行时覆盖其他进程刚写入的密钥。
(set -C; cat > .env <<EOF
APP_PORT=8080
POSTGRES_DB=chat
POSTGRES_USER=chat
POSTGRES_PASSWORD=$database_password
JWT_SECRET=$jwt_secret
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/chat
SPRING_DATASOURCE_USERNAME=chat
SPRING_DATASOURCE_PASSWORD=$database_password
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
EOF
)
printf '%s\n' '已生成 .env（权限 600），请勿提交或分享。'
