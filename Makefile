.PHONY: test build demo env up down logs

test:
	./mvnw -B verify

build:
	./mvnw -B package

# demo 使用内存数据库，不需要 PostgreSQL 或 Redis。
demo:
	JWT_SECRET="$${JWT_SECRET:-$$(openssl rand -hex 48)}" ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

env:
	./scripts/init-env.sh

up: env
	docker compose up --build -d

down:
	docker compose down

logs:
	docker compose logs -f app
