.PHONY: build-all build-account build-transfer up down logs

# 처음 시작할 때: 전체 빌드
build-all:
	./gradlew clean bootBuildImage

# 특정 서비스만 빌드
build-account:
	./gradlew :account-service:bootBuildImage

build-transfer:
	./gradlew :transfer-service:bootBuildImage

# 인프라와 앱 한 번에 띄우기
up:
	docker-compose up -d

# 전체 내리기 (볼륨 데이터까지 깔끔하게 삭제)
down:
	docker-compose down -v

# 코드 수정 후 특정 컨테이너만 재시작
# Account 코드를 수정했다면 터미널에 'make restart-account'
restart-account: build-account
	docker-compose up -d --no-deps --build account-service

restart-transfer: build-transfer
	docker-compose up -d --no-deps --build transfer-service

# 전체 로그 보기
logs:
	docker-compose logs -f

test:
	./gradlew test
