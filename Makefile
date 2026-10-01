.PHONY: build test run up down burst

build:
	./mvnw -B package -DskipTests

test:
	./mvnw -B verify

run:
	./mvnw -B spring-boot:run

up:
	docker compose up --build

down:
	docker compose down -v

burst:
	./burst.sh $(BASE_URL)
