.PHONY: demo test build

demo:
	JWT_SECRET="$${JWT_SECRET:-$$(openssl rand -hex 48)}" ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

test:
	./mvnw -B verify

build:
	./mvnw -B package
