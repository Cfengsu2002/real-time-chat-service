.PHONY: demo test build compose-up compose-scale compose-down

demo:
	JWT_SECRET="$${JWT_SECRET:-$$(openssl rand -hex 48)}" ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

test:
	./mvnw -B verify

build:
	./mvnw -B package

compose-up:
	docker compose up -d --build --wait

compose-scale:
	docker compose --profile scale up -d --build --wait

compose-down:
	docker compose --profile scale down
