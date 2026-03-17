## <img width="25" height="25" alt="Image" src="https://noticon-static.tammolo.com/dgggcrkxq/image/upload/v1662515801/noticon/dtalt5gl3xkddalhat8o.png" /> Toss Bank Clone

### 프로젝트 기간

`2026.03.16 ~ 2026.03.29`

### 📌 프로젝트 개요

토스뱅크 백엔드 직무에 지원하기 전에 **뱅킹 도메인**을 더 깊이 이해하고 실제로 어떤 문제를 풀어야 하는지 직접 경험해보고 싶어서 이 프로젝트를 시작했습니다.

평소 백엔드 개발을 하면서 일반적인 서비스 구조는 익숙했지만 은행 시스템은 일반 서비스와 다르게 **데이터 정합성, 동시성 제어, 분산 트랜잭션, 장애 상황 대응이 훨씬 더 중요하다고 생각**했습니다. 그래서 단순히 이론으로만 학습하기보다 **`계좌·입출금·이체`** 같은 핵심 기능을 직접 설계하고 구현하면서 실제로 어떤 문제가 발생하는지 확인해보고자 했습니다.

- 실제 정말 중요한 사용자의 돈과 관련되어 있으니깐!!!

이 프로젝트의 목표는 짧은 기간 안에 뱅킹 시스템 전체를 만드는 것이 아닌 **이체 도메인을 중심으로 핵심 문제를 빠르게 정의하고 하나씩 해결해보는 것입니다.**

> 특히 다음 세 가지를 집중적으로 검증하려고 했습니다.

1️⃣ 동시 요청이 몰릴 때 잔액 정합성을 어떻게 보장할 것인가

2️⃣ 서비스가 분리된 환경에서 이체 실패를 어떻게 복구할 것인가

3️⃣ 메시지 브로커나 외부 시스템 장애가 발생해도 핵심 송금 기능을 어떻게 안전하게 유지할 것인가

**즉, 이 프로젝트는 ‘완성된 은행 서비스’를 만드는 프로젝트라기보다 뱅킹 백엔드 개발자가 실제로 마주할 수 있는 문제를 작게라도 직접 설계하고 구현해보는 학습, 검증형 프로젝트입니다.**

### 🤖 **기술 스택 (Tech Stack)**

### Backend

![Java](https://img.shields.io/badge/Java-%23ED8B00.svg?style=flat&logo=openjdk&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-%237F52FF.svg?style=flat&logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=flat&logo=spring-boot&logoColor=white)
![Spring WebFlux](https://img.shields.io/badge/Spring_Webflux-6DB33F?style=flat&logo=spring-boot&logoColor=white)
![JPA/Hibernate](https://img.shields.io/badge/Hibernate-59666C?style=flat&logo=hibernate&logoColor=white)
![Gradle](https://img.shields.io/badge/gradle-02303A?style=flat&logo=gradle&logoColor=white)

### Frontend

![React](https://img.shields.io/badge/React-20232A?style=flat&logo=react&logoColor=61DAFB)
![TypeScript](https://img.shields.io/badge/TypeScript-007ACC?style=flat&logo=typescript&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat&logo=vite&logoColor=white)
![TailwindCSS](https://img.shields.io/badge/Tailwind_CSS-38B2AC?style=flat&logo=tailwind-css&logoColor=white)
![React Router](https://img.shields.io/badge/React_Router-CA4245?style=flat&logo=react-router&logoColor=white)

### DB

![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat&logo=MySQL&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-FF4438?style=flat&logo=Redis&logoColor=white)

### Message Broker

![Apache Kafka](https://img.shields.io/badge/kafka-231F20?style=flat&logo=apachekafka&logoColor=white)

### Infrastructure & Testing

![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=Docker&logoColor=white)
![Docker Compose](https://img.shields.io/badge/Docker_Compose-2496ED?style=flat&logo=docker_compose&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=flat&logo=k6&logoColor=white)
![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=flat&logo=JUnit5&logoColor=white)
![KoTest](https://img.shields.io/badge/kotest-00FF74?style=flat&logo=vitest&logoColor=white)

## 🚀 백엔드 실행 방법

### 1️⃣ 전체 빌드 (Docker 이미지 생성)

```
make build-all
```

또는 특정 서비스만:

```
make build-account
make build-transfer
```

---

### 2️⃣ 전체 서비스 실행

```
make up
```

실행되는 서비스:

- MySQL
- Redis
- Kafka
- Kafka UI
- api-gateway
- account-service
- transfer-service
- external-banking-service

---

### 3️⃣ 로그 확인

```
make logs
```

---

### 4️⃣ 특정 서비스 재시작 (코드 수정 후)

```
make restart-account
make restart-transfer
```

👉 해당 서비스만 다시 빌드 + 재실행됩니다.

---

### 5️⃣ 전체 종료 (데이터까지 삭제)

```
make down
```
